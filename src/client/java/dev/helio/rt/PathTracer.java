package dev.helio.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.VulkanCommandEncoderAccessor;
import dev.helio.ngx.Dlss;
import dev.helio.vk.GpuTimers;
import dev.helio.vk.Memory;
import dev.helio.vk.Shaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The path tracer. Runs once per frame inside vanilla's main pass, between the opaque and translucent
 * draws (see LevelRendererMixin): builds the {@link Scene}, then a compute pass in Mojang's command buffer
 * traces from the camera and overwrites the main colour wherever rays hit. Vanilla then draws water, glass,
 * clouds, weather and particles on top.
 * <p>
 * Mojang keeps every image in VK_IMAGE_LAYOUT_GENERAL and separates operations with full memory barriers,
 * so no layout transitions are needed; we add the same barrier after our dispatch.
 */
public final class PathTracer {
	private static final int PUSH_SIZE = 256;
	/** Accumulation keeps adapting slowly after this many frames instead of freezing. */
	private static final int MAX_FRAMES = 256;

	/** The projection vanilla draws the level with (view bobbing, nausea warp included). */
	public static final Matrix4f levelProjection = new Matrix4f();

	private static VulkanDevice device;
	private static long sampler, setLayout, textureSetLayout, descriptorPool, pipelineLayout, pipeline;
	private static long compositeSetLayout, compositeLayout, compositePipeline;
	private static long resolveSetLayout, resolveLayout, resolvePipeline;
	private static boolean dispatched;
	private static final int RING = 4;
	/** Entity texture arrays, one per frame in flight (rewritten {@value #RING} frames later). */
	private static final long[] textureSets = new long[RING];
	private static boolean failed, ranThisFrame;
	private static Vec3 cameraPos = Vec3.ZERO;
	private static final Matrix4f viewRotation = new Matrix4f();
	private static float sunAngle, moonAngle, rainBrightness;
	private static boolean hasSky, underwater;
	private static final Vector3f skyColor = new Vector3f();
	private static final Vector4f fogColor = new Vector4f();
	private static int seed;

	private static Memory.Img accum, entityImg;
	/** Frames averaged into {@link #accum} so far (0: next frame restarts it). For the overlay too. */
	public static int frameIndex;
	private static final Matrix4f lastInvViewProj = new Matrix4f();
	private static Vec3 lastCameraPos = Vec3.ZERO;
	private static float accumSunAngle;
	/** Per frame in flight: the shader's Frame block (last frame's camera for motion vectors, RR switch). */
	private static final Memory.Buf[] frameData = new Memory.Buf[RING];
	private static final Matrix4f lastViewProj = new Matrix4f();
	private static boolean lastUpscaled, lastRr;
	private static int jitterIndex;
	private static Vec3 rrOrigin;

	private PathTracer() {}

	/** Start of the level render. */
	public static void beginFrame(CameraRenderState camera, SkyRenderState sky) {
		cameraPos = camera.pos;
		viewRotation.set(camera.viewRotationMatrix);
		hasSky = sky.skybox == Skybox.OVERWORLD;
		if (hasSky) {
			sunAngle = sky.sunAngle;
			moonAngle = sky.moonAngle;
			rainBrightness = sky.rainBrightness;
			skyColor.set(sky.skyColor);
		}
		fogColor.set(camera.fogData.color);
		underwater = camera.fogType == FogType.WATER;
		ranThisFrame = false;
		dispatched = false;
	}

	/** Whether {@link #run} will replace vanilla's opaque picture this frame. */
	public static boolean active() {
		return !failed && Helio.rayTracing && HelioConfig.current.rayTracing && Helio.device != null && Minecraft.getInstance().level != null;
	}

	/** Between vanilla's opaque and translucent draws, with no render pass open. */
	public static void run() {
		if (ranThisFrame || !active()) return;
		ranThisFrame = true;
		try {
			if (device == null) init(Helio.device);
			VkCommandBuffer cmd = commandBuffer();
			Scene.update(cmd, cameraPos, true);
			if (Scene.tlas != 0) dispatch(cmd);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Path tracer failed; vanilla rendering for the rest of this session", t);
		}
	}

	/** After the level: with ray tracing off the scene still drains its queue (no GPU work). */
	public static void afterLevel() {
		if (ranThisFrame || !Helio.rayTracing || Helio.device == null || Minecraft.getInstance().level == null) return;
		Scene.update(commandBuffer(), cameraPos, false);
	}

	private static VkCommandBuffer commandBuffer() {
		return ((VulkanCommandEncoderAccessor) Helio.device.createCommandEncoder()).helio$commandBuffer();
	}

	private static void init(VulkanDevice dev) throws IOException {
		device = dev;
		VkDevice vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "sampler");
			sampler = h.get(0);

			// 0 TLAS, 2 and 5 sampled (atlas, vanilla depth), the rest storage images (see pathtrace.comp).
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(14, s);
			for (int i = 0; i < 14; i++) {
				int type = i == 0 ? VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
					: i == 2 || i == 5 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
				b.get(i).binding(i).descriptorType(type).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			// Push descriptors: no pool, and safe to rewrite every frame while earlier frames are in flight.
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);

			// Set 1: this frame's entity textures, bindless; slots past the frame's count stay unwritten.
			VkDescriptorSetLayoutBinding.Buffer tb = VkDescriptorSetLayoutBinding.calloc(1, s);
			tb.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(Entities.MAX_TEXTURES)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(tb)
				.pNext(VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(s).sType$Default()
					.pBindingFlags(s.ints(VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT))), null, h), "texture set layout");
			textureSetLayout = h.get(0);
			check(vkCreateDescriptorPool(vk, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(RING)
				.pPoolSizes(VkDescriptorPoolSize.calloc(1, s).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
					.descriptorCount(RING * Entities.MAX_TEXTURES)), null, h), "descriptor pool");
			descriptorPool = h.get(0);
			LongBuffer sets = s.mallocLong(RING);
			check(vkAllocateDescriptorSets(vk, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descriptorPool)
				.pSetLayouts(s.longs(textureSetLayout, textureSetLayout, textureSetLayout, textureSetLayout)), sets), "descriptor sets");
			sets.get(textureSets);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(PUSH_SIZE);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default()
				.pSetLayouts(s.longs(setLayout, textureSetLayout)).pPushConstantRanges(pc), null, h), "pipeline layout");
			pipelineLayout = h.get(0);

			pipeline = computePipeline(vk, "pathtrace.comp", pipelineLayout, s);

			VkDescriptorSetLayoutBinding.Buffer cb = VkDescriptorSetLayoutBinding.calloc(3, s);
			cb.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			cb.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			cb.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(cb), null, h), "composite set layout");
			compositeSetLayout = h.get(0);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(compositeSetLayout)), null, h), "composite layout");
			compositeLayout = h.get(0);
			compositePipeline = computePipeline(vk, "entity_composite.comp", compositeLayout, s);

			VkDescriptorSetLayoutBinding.Buffer rb = VkDescriptorSetLayoutBinding.calloc(5, s);
			for (int i = 0; i < 5; i++) {
				rb.get(i).binding(i).descriptorType(i == 3 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(rb), null, h), "resolve set layout");
			resolveSetLayout = h.get(0);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(resolveSetLayout)), null, h), "resolve layout");
			resolveLayout = h.get(0);
			resolvePipeline = computePipeline(vk, "rr_resolve.comp", resolveLayout, s);
			for (int i = 0; i < RING; i++) frameData[i] = Memory.buffer(256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		Helio.LOG.info("Path tracer initialised");
	}

	private static long computePipeline(VkDevice vk, String shader, long layout, MemoryStack s) throws IOException {
		LongBuffer h = s.mallocLong(1);
		ByteBuffer spv = Shaders.compile(shader);
		check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
		long module = h.get(0);
		MemoryUtil.memFree(spv);
		VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(layout);
		ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
		check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "compute pipeline");
		vkDestroyShaderModule(vk, module, null);
		return h.get(0);
	}

	/** After vanilla's solid features (with no render pass open): traced entity colour back over vanilla's. */
	public static void compositeEntities() {
		if (!dispatched || failed) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkCommandBuffer cmd = commandBuffer();
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			VulkanGpuTextureView color = (VulkanGpuTextureView) main.getColorTextureView();
			VulkanGpuTextureView depth = (VulkanGpuTextureView) main.getDepthTextureView();
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, compositePipeline);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(3, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(color.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(entityImg.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, compositeLayout, 0, w);
			vkCmdDispatch(cmd, (main.width + 7) / 8, (main.height + 7) / 8, 1);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Entity composite failed; vanilla rendering for the rest of this session", t);
		}
	}

	private static void dispatch(VkCommandBuffer cmd) throws Throwable {
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		VulkanGpuTextureView color = (VulkanGpuTextureView) main.getColorTextureView();
		VulkanGpuTextureView atlas = (VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		Vector3f sun = celestial(sunAngle), moon = celestial(moonAngle);
		float daylight = smoothstep(-0.05f, 0.2f, sun.y);
		Matrix4f viewProj = new Matrix4f(levelProjection).mul(viewRotation);
		Matrix4f invViewProj = new Matrix4f(viewProj).invert();
		boolean accumulate = HelioConfig.current.accumulate;
		// DLSS takes the traced picture at render size: Ray Reconstruction for path tracing, Super Resolution
		// otherwise. Reference accumulation and debug views stay native and composite themselves.
		boolean upscale = Dlss.available() && HelioConfig.current.debugView == HelioConfig.DebugView.OFF;
		boolean rr = upscale && Dlss.rayReconstruction();
		if (upscale && (!lastUpscaled || rr != lastRr)) Dlss.reset();
		lastUpscaled = upscale;
		lastRr = rr;

		try (MemoryStack s = MemoryStack.stackPush()) {
			ensureAccum(cmd, main.width, main.height, s);
			Dlss.ensureImages(cmd, main.width, main.height, s);
			int width = upscale ? Dlss.renderWidth : main.width, height = upscale ? Dlss.renderHeight : main.height;
			Vec3 cameraDelta = cameraPos.subtract(lastCameraPos);
			updateAccumulation(invViewProj);
			float jitterX = 0, jitterY = 0;
			if (accumulate || upscale) {
				int i = accumulate ? frameIndex + 1 : jitterIndex++ % Dlss.jitterPhases() + 1;
				jitterX = halton(i, 2) - 0.5f;
				jitterY = halton(i, 3) - 0.5f;
			}
			Memory.Buf frame = frameData[seed % RING];
			ByteBuffer fb = MemoryUtil.memByteBuffer(frame.mapped(), 256);
			lastViewProj.get(0, fb);
			fb.putFloat(64, (float) cameraDelta.x).putFloat(68, (float) cameraDelta.y).putFloat(72, (float) cameraDelta.z)
				.putFloat(76, accumulate ? 3 : 1).putInt(80, upscale ? 1 : 0)
				.putFloat(84, (float) (0.02 + 0.1 * mc.options.gamma().get()))
				.putInt(88, HelioConfig.current.lighting == HelioConfig.Lighting.HYBRID ? 1 : 0);
			lastViewProj.set(viewProj);
			GpuTimers.begin(cmd, GpuTimers.Pass.PATH_TRACE);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(14, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
				.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default().pAccelerationStructures(s.longs(Scene.tlas)));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(color.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(3).sType$Default().dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(accum.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(4).sType$Default().dstBinding(4).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(entityImg.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			VulkanGpuTextureView depth = (VulkanGpuTextureView) main.getDepthTextureView();
			w.get(5).sType$Default().dstBinding(5).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			Memory.Img[] inputs = {Dlss.color, Dlss.diffuse, Dlss.specular, Dlss.normals, Dlss.depth, Dlss.motion, Dlss.hitDistance, Dlss.hitDepth};
			for (int i = 0; i < inputs.length; i++) {
				w.get(6 + i).sType$Default().dstBinding(6 + i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
					.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(inputs[i].view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			}
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, w);
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 1, s.longs(writeEntityTextures(mc, s)), null);

			ByteBuffer pc = s.malloc(PUSH_SIZE);
			invViewProj.get(0, pc);
			pc.putFloat(64, (float) (cameraPos.x - Scene.anchorX)).putFloat(68, (float) (cameraPos.y - Scene.anchorY))
				.putFloat(72, (float) (cameraPos.z - Scene.anchorZ)).putFloat(76, HelioConfig.current.debugView.ordinal())
				.putFloat(80, sun.x).putFloat(84, sun.y).putFloat(88, sun.z).putFloat(92, daylight)
				.putLong(96, Scene.instanceInfo)
				.putFloat(104, Scene.rtDistanceBlocks).putInt(108, frameIndex)
				.putFloat(112, jitterX).putFloat(116, jitterY)
				// Sampling the sun disc is noisy at 1 sample per pixel: only with accumulation until M4's denoiser.
				.putInt(120, seed++).putFloat(124, accumulate || rr ? 1 : 0)
				.putFloat(128, moon.x).putFloat(132, moon.y).putFloat(136, moon.z).putFloat(140, rainBrightness)
				.putFloat(144, skyColor.x).putFloat(148, skyColor.y).putFloat(152, skyColor.z).putFloat(156, hasSky ? 1 : 0)
				.putFloat(160, fogColor.x).putFloat(164, fogColor.y).putFloat(168, fogColor.z).putFloat(172, underwater ? 1 : 0);
			new Matrix4f(levelProjection).mul(viewRotation).get(176, pc);
			pc.putLong(240, Scene.lights).putLong(248, frame.address());
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

			vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
			GpuTimers.end(cmd, GpuTimers.Pass.PATH_TRACE);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			if (accumulate) frameIndex = Math.min(frameIndex + 1, MAX_FRAMES);
			if (upscale) {
				GpuTimers.begin(cmd, GpuTimers.Pass.DLSS);
				// DLSS's jitter is how far the projection moved the picture: the opposite of where our samples went.
				// RR's world: camera-relative axes (as the normals), positions from a fixed origin near the player.
				if (rrOrigin == null || rrOrigin.distanceToSqr(cameraPos) > 4096 * 4096) rrOrigin = cameraPos;
				Matrix4f worldToView = new Matrix4f(viewRotation).translate((float) (rrOrigin.x - cameraPos.x),
					(float) (rrOrigin.y - cameraPos.y), (float) (rrOrigin.z - cameraPos.z));
				boolean ok = Dlss.evaluate(cmd, rr, -jitterX, -jitterY, worldToView, levelProjection);
				GpuTimers.end(cmd, GpuTimers.Pass.DLSS);
				VulkanCommandEncoder.memoryBarrier(cmd, s);
				if (ok) resolve(cmd, color, depth, main.width, main.height, s);
			}
			dispatched = true;
		}
	}

	/** After DLSS: its output, tonemapped, into the main colour (rr_resolve.comp). */
	private static void resolve(VkCommandBuffer cmd, VulkanGpuTextureView color, VulkanGpuTextureView depth, int width, int height, MemoryStack s) {
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipeline);
		long[] views = {Dlss.output.view(), color.vkImageView(), entityImg.view(), 0, Dlss.hitDepth.view()};
		VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(5, s);
		for (int i = 0; i < 5; i++) {
			if (i == 3) continue;
			w.get(i).sType$Default().dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(views[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		}
		w.get(3).sType$Default().dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, resolveLayout, 0, w);
		vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
		VulkanCommandEncoder.memoryBarrier(cmd, s);
	}

	private static long writeEntityTextures(Minecraft mc, MemoryStack s) {
		long set = textureSets[seed % RING];
		int n = Entities.textures.size();
		if (n == 0) return set;
		VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(n, s);
		for (int i = 0; i < n; i++) {
			VulkanGpuTextureView view = (VulkanGpuTextureView) mc.getTextureManager().getTexture(Entities.textures.get(i)).getTextureView();
			images.get(i).sampler(sampler).imageView(view.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
		}
		vkUpdateDescriptorSets(device.vkDevice(), VkWriteDescriptorSet.calloc(1, s).sType$Default().dstSet(set).dstBinding(0)
			.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(n).pImageInfo(images), null);
		return set;
	}

	/**
	 * Reference accumulation restarts when the view changes (camera, projection, debug view), the sun jumps
	 * (/time set), or blocks change right next to the camera. Distant rebuilds (grass spreading, leaf decay)
	 * and the sun's slow drift just blend in: past {@value #MAX_FRAMES} frames the average keeps adapting.
	 * Moving entities smear; it's a reference mode for still scenes.
	 */
	private static void updateAccumulation(Matrix4f invViewProj) {
		boolean restart = !HelioConfig.current.accumulate || HelioConfig.current.debugView != HelioConfig.DebugView.OFF
			|| !invViewProj.equals(lastInvViewProj) || !cameraPos.equals(lastCameraPos)
			|| Math.abs(sunAngle - accumSunAngle) > 0.05f || Scene.nearbyRebuild;
		lastInvViewProj.set(invViewProj);
		lastCameraPos = cameraPos;
		if (restart) {
			frameIndex = 0;
			accumSunAngle = sunAngle;
		}
	}

	private static void ensureAccum(VkCommandBuffer cmd, int width, int height, MemoryStack s) {
		if (accum != null && accum.width() == width && accum.height() == height) return;
		if (accum != null) {
			Memory.Img oldAccum = accum, oldEntity = entityImg;
			Scene.defer(() -> {
				oldAccum.free();
				oldEntity.free();
			});
		}
		accum = Memory.storageImage(width, height, VK_FORMAT_R32G32B32A32_SFLOAT);
		entityImg = Memory.storageImage(width, height, VK_FORMAT_R32G32B32A32_SFLOAT);
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(2, s);
		for (int i = 0; i < 2; i++) {
			barrier.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image((i == 0 ? accum : entityImg).image()).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
				.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		}
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
		frameIndex = 0;
	}

	private static float halton(int i, int base) {
		float f = 1, r = 0;
		for (; i > 0; i /= base) {
			f /= base;
			r += f * (i % base);
		}
		return r;
	}

	/** Device closing; the GPU is idle. */
	public static void destroy() {
		if (device == null) return;
		VkDevice vk = device.vkDevice();
		if (accum != null) {
			accum.free();
			entityImg.free();
		}
		vkDestroyPipeline(vk, pipeline, null);
		vkDestroyPipelineLayout(vk, pipelineLayout, null);
		Dlss.destroyImages();
		for (Memory.Buf b : frameData) if (b != null) b.free();
		vkDestroyPipeline(vk, resolvePipeline, null);
		vkDestroyPipelineLayout(vk, resolveLayout, null);
		vkDestroyDescriptorSetLayout(vk, resolveSetLayout, null);
		vkDestroyPipeline(vk, compositePipeline, null);
		vkDestroyPipelineLayout(vk, compositeLayout, null);
		vkDestroyDescriptorSetLayout(vk, compositeSetLayout, null);
		vkDestroyDescriptorSetLayout(vk, setLayout, null);
		vkDestroyDescriptorPool(vk, descriptorPool, null);
		vkDestroyDescriptorSetLayout(vk, textureSetLayout, null);
		vkDestroySampler(vk, sampler, null);
	}

	/** Direction of the sun or moon, as vanilla's sky renderer places them. */
	private static Vector3f celestial(float angle) {
		return new Matrix4f().rotateY((float) (-Math.PI / 2)).rotateX(angle).transformDirection(new Vector3f(0, 1, 0));
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3 - 2 * t);
	}
}
