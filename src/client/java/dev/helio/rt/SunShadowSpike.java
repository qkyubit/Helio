package dev.helio.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.VulkanCommandEncoderAccessor;
import dev.helio.vk.GpuTimers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Ray-traced sun shadows over the vanilla frame (M0 spike, now on the {@link Scene}): after vanilla's level
 * frame graph, a compute dispatch in Mojang's own command buffer traces one ray per pixel to the sun and
 * darkens the colour target. Cutout geometry (leaves, plants) is alpha-tested against the block atlas.
 * <p>
 * Mojang keeps every image in VK_IMAGE_LAYOUT_GENERAL and separates operations with full memory
 * barriers, so no layout transitions are needed; we add the same barrier after our dispatch.
 */
public final class SunShadowSpike {
	private static final float SHADOW_STRENGTH = 0.55f;
	private static final int PUSH_SIZE = 104;

	private static VulkanDevice device;
	private static long sampler, setLayout, pipelineLayout, pipeline;
	private static boolean failed;

	public static final Matrix4f levelProjection = new Matrix4f();

	private SunShadowSpike() {}

	/** Called right after vanilla's level frame graph executed. */
	public static void afterLevel(Vec3 cameraPos, Matrix4f viewRotation, float sunAngle) {
		if (failed || !Helio.rayTracing || Helio.device == null || Minecraft.getInstance().level == null) return;
		try {
			if (device == null) init(Helio.device);
			VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) device.createCommandEncoder()).helio$commandBuffer();
			boolean on = HelioConfig.current.rayTracing;
			Scene.update(cmd, SectionPos.of(cameraPos), on);
			if (!on || Scene.tlas == 0) return;

			Vector3f sun = new Matrix4f().rotateY((float) (-Math.PI / 2)).rotateX(sunAngle).transformDirection(new Vector3f(0, 1, 0));
			if (sun.y < 0.02f) return; // ponytail: night = no shadows; moon light is M3
			dispatch(cmd, cameraPos, viewRotation, sun);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Sun shadows failed; disabled for this session", t);
		}
	}

	private static void init(VulkanDevice dev) throws IOException {
		device = dev;
		VkDevice vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "sampler");
			sampler = h.get(0);

			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			// Push descriptors: no pool, and safe to rewrite every frame while earlier frames are in flight.
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);

			VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(PUSH_SIZE);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default()
				.pSetLayouts(s.longs(setLayout)).pPushConstantRanges(pc), null, h), "pipeline layout");
			pipelineLayout = h.get(0);

			ByteBuffer spv = compile("sun_shadow.comp");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
			long module = h.get(0);
			MemoryUtil.memFree(spv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
			check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "compute pipeline");
			pipeline = h.get(0);
			vkDestroyShaderModule(vk, module, null);
		}
		Helio.LOG.info("Sun shadows initialised");
	}

	/** GLSL from the jar to SPIR-V 1.4 via the shaderc Minecraft already ships. */
	public static ByteBuffer compile(String name) throws IOException {
		String src;
		try (InputStream in = SunShadowSpike.class.getResourceAsStream("/assets/helio/shaders/" + name)) {
			if (in == null) throw new IOException("missing shader " + name);
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		long compiler = shaderc_compiler_initialize(), opts = shaderc_compile_options_initialize();
		try {
			shaderc_compile_options_set_target_env(opts, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(opts, shaderc_spirv_version_1_4);
			shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_performance);
			long result = shaderc_compile_into_spv(compiler, src, shaderc_compute_shader, name, "main", opts);
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
					throw new IllegalStateException(name + ": " + shaderc_result_get_error_message(result));
				ByteBuffer bytes = shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compile_options_release(opts);
			shaderc_compiler_release(compiler);
		}
	}

	private static void dispatch(VkCommandBuffer cmd, Vec3 cameraPos, Matrix4f viewRotation, Vector3f sun) {
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		VulkanGpuTextureView color = (VulkanGpuTextureView) main.getColorTextureView();
		VulkanGpuTextureView depth = (VulkanGpuTextureView) main.getDepthTextureView();
		VulkanGpuTextureView atlas = (VulkanGpuTextureView) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();

		try (MemoryStack s = MemoryStack.stackPush()) {
			GpuTimers.begin(cmd, GpuTimers.Pass.SUN_SHADOWS);

			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(4, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
				.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default().pAccelerationStructures(s.longs(Scene.tlas)));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depth.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(color.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(3).sType$Default().dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, w);

			ByteBuffer pc = s.malloc(PUSH_SIZE);
			new Matrix4f(levelProjection).mul(viewRotation).invert().get(0, pc);
			pc.putFloat(64, (float) (cameraPos.x - Scene.anchorX)).putFloat(68, (float) (cameraPos.y - Scene.anchorY))
				.putFloat(72, (float) (cameraPos.z - Scene.anchorZ)).putFloat(76, 0)
				.putFloat(80, sun.x).putFloat(84, sun.y).putFloat(88, sun.z).putFloat(92, SHADOW_STRENGTH)
				.putLong(96, Scene.instanceInfo);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

			vkCmdDispatch(cmd, (main.width + 7) / 8, (main.height + 7) / 8, 1);
			GpuTimers.end(cmd, GpuTimers.Pass.SUN_SHADOWS);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		}
	}
}
