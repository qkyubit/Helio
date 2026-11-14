package dev.helio.rt;

import dev.helio.vk.Memory;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.LongBuffer;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Bloom (M6 step 2): a mip chain of DLSS's HDR output, each level half the last, blurred back up into the first
 * (bloom.comp). rr_resolve.comp mixes a small share of it into the picture before the exposure and tonemap, so
 * what's brightest (the sun, emissive blocks, glints) glows. Only after DLSS: reference accumulation has none.
 */
final class Bloom {
	/** Levels, the first half the window's size. Six reach about 1/64 of the screen. */
	static final int LEVELS = 6;
	private static final int FIRST_DOWN = 0, DOWN = 1, UP = 2;

	private static long setLayout, layout, pipeline, linear;
	private static Memory.Img[] mips;

	private Bloom() {}

	static void init(VkDevice vk, MemoryStack s) throws IOException {
		LongBuffer h = s.mallocLong(1);
		check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
			.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
			.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, h), "bloom sampler");
		linear = h.get(0);
		VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, s);
		b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
		b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
		check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
			.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "bloom set layout");
		setLayout = h.get(0);
		check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout))
			.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(4)), null, h), "bloom layout");
		layout = h.get(0);
		pipeline = PathTracer.computePipeline(vk, "bloom.comp", layout, s);
	}

	/** The blurred picture at half the window's size, for rr_resolve.comp (sample with {@link #sampler()}). */
	static long view() {
		return mips[0].view();
	}

	static long sampler() {
		return linear;
	}

	/** Builds the chain from src (window-sized HDR, GENERAL layout). */
	static void run(VkCommandBuffer cmd, long src, int width, int height, MemoryStack s) {
		ensure(cmd, width, height, s);
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		for (int i = 0; i < LEVELS; i++) pass(cmd, i == 0 ? src : mips[i - 1].view(), mips[i], i == 0 ? FIRST_DOWN : DOWN, s);
		for (int i = LEVELS - 1; i > 0; i--) pass(cmd, mips[i].view(), mips[i - 1], UP, s);
	}

	private static void pass(VkCommandBuffer cmd, long src, Memory.Img dst, int mode, MemoryStack s) {
		VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(2, s);
		w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(linear).imageView(src).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(dst.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, w);
		vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, s.ints(mode));
		vkCmdDispatch(cmd, (dst.width() + 7) / 8, (dst.height() + 7) / 8, 1);
		VulkanCommandEncoder.memoryBarrier(cmd, s);
	}

	private static void ensure(VkCommandBuffer cmd, int width, int height, MemoryStack s) {
		int w0 = Math.max(1, width / 2), h0 = Math.max(1, height / 2);
		if (mips != null && mips[0].width() == w0 && mips[0].height() == h0) return;
		if (mips != null) {
			Memory.Img[] old = mips;
			Scene.defer(() -> {
				for (Memory.Img m : old) m.free();
			});
		}
		mips = new Memory.Img[LEVELS];
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(LEVELS, s);
		for (int i = 0; i < LEVELS; i++) {
			mips[i] = Memory.storageImage(Math.max(1, w0 >> i), Math.max(1, h0 >> i), VK_FORMAT_R16G16B16A16_SFLOAT);
			barrier.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(mips[i].image()).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
				.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		}
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
	}

	static void destroy(VkDevice vk) {
		if (mips != null) for (Memory.Img m : mips) m.free();
		mips = null;
		vkDestroyPipeline(vk, pipeline, null);
		vkDestroyPipelineLayout(vk, layout, null);
		vkDestroyDescriptorSetLayout(vk, setLayout, null);
		vkDestroySampler(vk, linear, null);
	}
}
