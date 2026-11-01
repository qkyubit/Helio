package dev.helio.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.VulkanCommandEncoderAccessor;
import dev.helio.vk.GpuTimers;
import dev.helio.vk.Shaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
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
	private static final int PUSH_SIZE = 112;

	/** The projection vanilla draws the level with (view bobbing, nausea warp included). */
	public static final Matrix4f levelProjection = new Matrix4f();

	private static VulkanDevice device;
	private static long sampler, setLayout, pipelineLayout, pipeline;
	private static boolean failed, ranThisFrame;
	private static Vec3 cameraPos = Vec3.ZERO;
	private static final Matrix4f viewRotation = new Matrix4f();
	private static float sunAngle;

	private PathTracer() {}

	/** Start of the level render. */
	public static void beginFrame(Vec3 camera, Matrix4fc rotation, float sun) {
		cameraPos = camera;
		viewRotation.set(rotation);
		sunAngle = sun;
		ranThisFrame = false;
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

			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(3, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			// Push descriptors: no pool, and safe to rewrite every frame while earlier frames are in flight.
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(PUSH_SIZE);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default()
				.pSetLayouts(s.longs(setLayout)).pPushConstantRanges(pc), null, h), "pipeline layout");
			pipelineLayout = h.get(0);

			ByteBuffer spv = Shaders.compile("pathtrace.comp");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
			long module = h.get(0);
			MemoryUtil.memFree(spv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
			check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "compute pipeline");
			pipeline = h.get(0);
			vkDestroyShaderModule(vk, module, null);
		}
		Helio.LOG.info("Path tracer initialised");
	}

	private static void dispatch(VkCommandBuffer cmd) {
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		VulkanGpuTextureView color = (VulkanGpuTextureView) main.getColorTextureView();
		VulkanGpuTextureView atlas = (VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		Vector3f sun = new Matrix4f().rotateY((float) (-Math.PI / 2)).rotateX(sunAngle).transformDirection(new Vector3f(0, 1, 0));
		float daylight = smoothstep(-0.05f, 0.2f, sun.y);

		try (MemoryStack s = MemoryStack.stackPush()) {
			GpuTimers.begin(cmd, GpuTimers.Pass.PATH_TRACE);
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(3, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
				.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default().pAccelerationStructures(s.longs(Scene.tlas)));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(color.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, w);

			ByteBuffer pc = s.malloc(PUSH_SIZE);
			new Matrix4f(levelProjection).mul(viewRotation).invert().get(0, pc);
			pc.putFloat(64, (float) (cameraPos.x - Scene.anchorX)).putFloat(68, (float) (cameraPos.y - Scene.anchorY))
				.putFloat(72, (float) (cameraPos.z - Scene.anchorZ)).putFloat(76, HelioConfig.current.debugView.ordinal())
				.putFloat(80, sun.x).putFloat(84, sun.y).putFloat(88, sun.z).putFloat(92, daylight)
				.putLong(96, Scene.instanceInfo)
				.putFloat(104, Scene.rtDistanceBlocks).putFloat(108, 0);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

			vkCmdDispatch(cmd, (main.width + 7) / 8, (main.height + 7) / 8, 1);
			GpuTimers.end(cmd, GpuTimers.Pass.PATH_TRACE);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		}
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0f, 1f);
		return t * t * (3 - 2 * t);
	}
}
