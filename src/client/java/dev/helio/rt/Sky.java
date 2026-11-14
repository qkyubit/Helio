package dev.helio.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.helio.vk.Memory;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The physically based sky's LUTs (M6 step 3, sky.comp): transmittance and multiple scattering once, then every
 * frame the sky view for this sun and moon and the ambient cube from it. pathtrace.comp samples them for the
 * sky it draws, the sun and moon light reaching the ground, and sky light.
 */
final class Sky {
	/** Light at the top of the atmosphere, as pathtrace.comp's SUN_TOP and MOON_TOP. */
	static final float SUN_TOP = 2.9f, MOON_TOP = 0.23f;
	/** Share of the moon's light the night sky scatters (its light on the ground stays): at 1 nights read like dusk. */
	private static final float MOON_SKY = 0.1f;
	/** Sun and moon light left in full rain (pathtrace.comp RAIN_LIGHT): about as dark as a clear night. */
	static final float RAIN_LIGHT = 0.02f;
	private static final int TRANSMITTANCE = 0, MULTI = 1, SKY_VIEW = 2, AMBIENT = 3;

	private static long setLayout, layout, pipeline, linear;
	private static Memory.Img transmittance, multi, skyView, ambient;
	private static boolean ready;

	private Sky() {}

	static void init(VkDevice vk, MemoryStack s) throws IOException {
		LongBuffer h = s.mallocLong(1);
		check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
			.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
			.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, h), "sky sampler");
		linear = h.get(0);
		VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(7, s);
		for (int i = 0; i < 7; i++) {
			b.get(i).binding(i).descriptorType(i < 4 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
		}
		check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
			.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "sky set layout");
		setLayout = h.get(0);
		check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout))
			.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(36)), null, h), "sky layout");
		layout = h.get(0);
		pipeline = PathTracer.computePipeline(vk, "sky.comp", layout, s);
		transmittance = Memory.storageImage(256, 64, VK_FORMAT_R16G16B16A16_SFLOAT);
		multi = Memory.storageImage(32, 32, VK_FORMAT_R16G16B16A16_SFLOAT);
		skyView = Memory.storageImage(256, 128, VK_FORMAT_R16G16B16A16_SFLOAT);
		ambient = Memory.storageImage(6, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
		ready = false;
	}

	static long sampler() {
		return linear;
	}

	static long transmittanceView() {
		return transmittance.view();
	}

	static long skyViewView() {
		return skyView.view();
	}

	static long ambientView() {
		return ambient.view();
	}

	/** Before the path trace. rain: vanilla's rain brightness (1 clear .. 0 raining), dimming as pathtrace.comp does. */
	static void update(VkCommandBuffer cmd, Vector3f sun, Vector3f moon, float rain, MemoryStack s) {
		if (!ready) {
			Memory.Img[] all = {transmittance, multi, skyView, ambient};
			VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(all.length, s);
			for (int i = 0; i < all.length; i++) {
				barrier.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
					.image(all[i].image()).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
					.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			}
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
		}
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(7, s);
		long[] storage = {transmittance.view(), multi.view(), skyView.view(), ambient.view()};
		for (int i = 0; i < 4; i++) {
			w.get(i).sType$Default().dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(storage[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		}
		long[] sampled = {transmittance.view(), multi.view(), skyView.view()};
		for (int i = 0; i < 3; i++) {
			w.get(4 + i).sType$Default().dstBinding(4 + i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(linear).imageView(sampled[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		}
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, w);
		ByteBuffer pc = s.malloc(36);
		float dim = RAIN_LIGHT + (1 - RAIN_LIGHT) * rain;
		pc.putFloat(0, sun.x).putFloat(4, sun.y).putFloat(8, sun.z).putFloat(12, SUN_TOP * dim)
			.putFloat(16, moon.x).putFloat(20, moon.y).putFloat(24, moon.z).putFloat(28, MOON_TOP * MOON_SKY * dim);
		if (!ready) {
			pass(cmd, pc, TRANSMITTANCE, transmittance, s);
			pass(cmd, pc, MULTI, multi, s);
			ready = true;
		}
		pass(cmd, pc, SKY_VIEW, skyView, s);
		pass(cmd, pc, AMBIENT, ambient, s);
	}

	private static void pass(VkCommandBuffer cmd, ByteBuffer pc, int mode, Memory.Img target, MemoryStack s) {
		pc.putInt(32, mode);
		vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
		vkCmdDispatch(cmd, (target.width() + 7) / 8, (target.height() + 7) / 8, 1);
		VulkanCommandEncoder.memoryBarrier(cmd, s);
	}

	static void destroy(VkDevice vk) {
		for (Memory.Img img : new Memory.Img[]{transmittance, multi, skyView, ambient}) if (img != null) img.free();
		transmittance = multi = skyView = ambient = null;
		vkDestroyPipeline(vk, pipeline, null);
		vkDestroyPipelineLayout(vk, layout, null);
		vkDestroyDescriptorSetLayout(vk, setLayout, null);
		vkDestroySampler(vk, linear, null);
	}
}
