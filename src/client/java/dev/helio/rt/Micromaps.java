package dev.helio.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.vk.Memory;
import dev.helio.vk.Memory.Buf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.vulkan.EXTOpacityMicromap.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Opacity micromaps for cutout section layers (leaves, plants, glass panes...): a compute pass marks each
 * micro-triangle opaque, clear or mixed from the block atlas, then the driver builds the micromap the
 * cutout BLAS references. Rays then skip the alpha test everywhere except mixed micro-triangles.
 */
final class Micromaps {
	/** 256 micro-triangles per triangle, 2 bits each. */
	static final int LEVEL = 4, BYTES_PER_TRIANGLE = 64;
	private static final int TRIANGLE_DESC_SIZE = 8; // VkMicromapTriangleEXT
	private static final int RING = 4;

	record Micromap(Buf storage, long handle) {
		void free(VkDevice vk) {
			vkDestroyMicromapEXT(vk, handle, null);
			storage.free();
		}
	}

	private static VkDevice vk;
	private static long sampler, setLayout, pipelineLayout, pipeline;
	private static final Buf[] inputs = new Buf[RING];
	private static Buf scratch, sequence;
	private static int sequenceCapacity;

	private Micromaps() {}

	private static void init(VkDevice device) throws IOException {
		vk = device;
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "sampler");
			sampler = h.get(0);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout))
				.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(32)), null, h), "pipeline layout");
			pipelineLayout = h.get(0);
			ByteBuffer spv = SunShadowSpike.compile("omm.comp");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
			long module = h.get(0);
			MemoryUtil.memFree(spv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
			check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "compute pipeline");
			pipeline = h.get(0);
			vkDestroyShaderModule(vk, module, null);
		}
	}

	/**
	 * Records the opacity pass and micromap builds for geometries given by vertex-buffer address and
	 * triangle count. Ends with a barrier, so BLAS builds recorded next can reference the results.
	 */
	static Micromap[] build(VkDevice device, VkCommandBuffer cmd, int slot, long[] vertices, int[] triangles, MemoryStack s) throws IOException {
		if (pipeline == 0) init(device);
		int n = vertices.length;
		long[] dataOffset = new long[n], descOffset = new long[n];
		long total = 0;
		for (int i = 0; i < n; i++) {
			dataOffset[i] = total;
			total = align(total + (long) triangles[i] * BYTES_PER_TRIANGLE, 256);
			descOffset[i] = total;
			total = align(total + (long) triangles[i] * TRIANGLE_DESC_SIZE, 256);
		}
		Buf input = inputs[slot];
		if (input == null || input.size() < total) {
			if (input != null) input.free(); // this slot's last use was RING frames ago
			input = inputs[slot] = Memory.buffer(Math.max(total * 3 / 2, 1 << 20),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT, false);
		}

		// Opacity states from the atlas.
		VulkanGpuTextureView atlas = (VulkanGpuTextureView) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, VkWriteDescriptorSet.calloc(1, s).sType$Default()
			.dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)));
		ByteBuffer pc = s.malloc(32);
		for (int i = 0; i < n; i++) {
			pc.putLong(0, vertices[i]).putLong(8, input.address() + dataOffset[i]).putLong(16, input.address() + descOffset[i]).putInt(24, triangles[i]);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
			vkCmdDispatch(cmd, (triangles[i] * 16 + 63) / 64, 1, 1);
		}
		VulkanCommandEncoder.memoryBarrier(cmd, s);

		// Micromaps.
		Micromap[] result = new Micromap[n];
		VkMicromapBuildInfoEXT.Buffer infos = VkMicromapBuildInfoEXT.calloc(n);
		VkMicromapUsageEXT.Buffer usages = VkMicromapUsageEXT.calloc(n);
		try {
			VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(s).sType$Default();
			long[] scratchOffset = new long[n];
			long scratchTotal = 0;
			LongBuffer h = s.mallocLong(1);
			for (int i = 0; i < n; i++) {
				usages.get(i).count(triangles[i]).subdivisionLevel(LEVEL).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
				VkMicromapBuildInfoEXT info = infos.get(i).sType$Default().type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
					.flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT).mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
					.pUsageCounts(usages.slice(i, 1)).usageCountsCount(1) // shared count with ppUsageCounts: set by hand
					.triangleArrayStride(TRIANGLE_DESC_SIZE);
				info.data().deviceAddress(input.address() + dataOffset[i]);
				info.triangleArray().deviceAddress(input.address() + descOffset[i]);
				vkGetMicromapBuildSizesEXT(vk, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, sizes);
				Buf storage = Memory.buffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false);
				check(vkCreateMicromapEXT(vk, VkMicromapCreateInfoEXT.calloc(s).sType$Default().buffer(storage.buffer())
					.size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT), null, h), "create micromap");
				result[i] = new Micromap(storage, h.get(0));
				info.dstMicromap(h.get(0));
				scratchOffset[i] = scratchTotal;
				scratchTotal = align(scratchTotal + sizes.buildScratchSize(), 256);
			}
			if (scratch == null || scratch.size() < scratchTotal) {
				if (scratch != null) {
					Buf old = scratch;
					Scene.defer(old::free);
				}
				scratch = Memory.buffer(Math.max(scratchTotal, 16L << 20), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			}
			for (int i = 0; i < n; i++) infos.get(i).scratchData().deviceAddress(scratch.address() + scratchOffset[i]);
			vkCmdBuildMicromapsEXT(cmd, infos);
		} finally {
			infos.free();
			usages.free();
		}
		VulkanCommandEncoder.memoryBarrier(cmd, s);
		return result;
	}

	/** Index buffer 0, 1, 2, ...: triangle i uses micromap entry i. */
	static long sequence(int triangles) {
		if (triangles > sequenceCapacity) {
			if (sequence != null) {
				Buf old = sequence;
				Scene.defer(old::free);
			}
			sequenceCapacity = Math.max(1 << 17, Integer.highestOneBit(triangles) * 2);
			sequence = Memory.buffer(sequenceCapacity * 4L, KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
			IntBuffer ints = MemoryUtil.memIntBuffer(sequence.mapped(), sequenceCapacity);
			for (int i = 0; i < sequenceCapacity; i++) ints.put(i, i);
		}
		return sequence.address();
	}

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	static void destroy() {
		for (Buf b : inputs) if (b != null) b.free();
		if (scratch != null) scratch.free();
		if (sequence != null) sequence.free();
		if (pipeline == 0) return;
		vkDestroyPipeline(vk, pipeline, null);
		vkDestroyPipelineLayout(vk, pipelineLayout, null);
		vkDestroyDescriptorSetLayout(vk, setLayout, null);
		vkDestroySampler(vk, sampler, null);
	}
}
