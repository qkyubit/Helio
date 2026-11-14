package dev.helio.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.vk.Memory;
import dev.helio.vk.Memory.Buf;
import dev.helio.vk.Shaders;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTOpacityMicromap.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Opacity micromaps for cutout section layers (leaves, plants, glass panes...): a compute pass marks each
 * micro-triangle opaque, clear or mixed from the block atlas, then the driver builds the micromap the
 * cutout BLAS references. Rays then skip the alpha test everywhere except mixed micro-triangles.
 * <p>
 * Triangles with the same UVs (the same face of the same model, all over the section) share one micromap entry,
 * and each entry is subdivided until a micro-triangle covers about one texel: a 128x pack's leaves would
 * otherwise be mixed nearly everywhere (falling back to the alpha test) and, sampled sparsely, could come out
 * opaque where the texture is clear.
 */
final class Micromaps {
	/** Finest subdivision: 16384 micro-triangles, 4 KB per entry. ponytail: 256x+ packs get coarser entries. */
	static final int MAX_LEVEL = 7;
	private static final int ENTRY_SIZE = 32; // omm.comp's Entry
	private static final int TRIANGLE_DESC_SIZE = 8; // VkMicromapTriangleEXT
	private static final int RING = 4;

	/** Block atlas size in texels, for subdivision levels (set when the atlas uploads; 0 before). */
	static volatile int atlasWidth, atlasHeight;

	/** A cutout layer's micromap: its entries' storage, and the triangle -> entry index the BLAS build reads. */
	record Micromap(Buf storage, long handle, Buf index, int[] triangleLevels) {
		void free(VkDevice vk) {
			vkDestroyMicromapEXT(vk, handle, null);
			storage.free();
			index.free();
		}
	}

	/** Worked out on the compile worker: unique UV triangles and which one each triangle uses. */
	record Plan(int[] triangleEntry, float[] uvs, byte[] levels) {
		int entries() {
			return levels.length;
		}
	}

	private static VkDevice vk;
	private static long sampler, setLayout, pipelineLayout, pipeline;
	private static final Buf[] inputs = new Buf[RING], entryBufs = new Buf[RING];
	private static Buf scratch;

	private Micromaps() {}

	/** Compile worker: dedupe a cutout layer's triangles by UV (vertices in vanilla's BLOCK format at {@code address}). */
	static Plan plan(long address, int quads) {
		float aw = Math.max(atlasWidth, 1), ah = Math.max(atlasHeight, 1);
		boolean known = atlasWidth > 0;
		Object2IntOpenHashMap<Key> ids = new Object2IntOpenHashMap<>();
		int[] triangleEntry = new int[quads * 2];
		float[] uvs = new float[64 * 6];
		byte[] levels = new byte[64];
		int[][] corners = {{0, 1, 2}, {2, 3, 0}}; // quads are indexed 0 1 2, 2 3 0
		float[] t = new float[6];
		for (int q = 0; q < quads; q++) {
			for (int half = 0; half < 2; half++) {
				for (int c = 0; c < 3; c++) {
					long v = address + (long) (q * 4 + corners[half][c]) * Scene.VERTEX_STRIDE;
					t[c * 2] = memGetFloat(v + 16);
					t[c * 2 + 1] = memGetFloat(v + 20);
				}
				Key key = new Key(t);
				int id = ids.getOrDefault(key, -1);
				if (id < 0) {
					id = ids.size();
					ids.put(key, id);
					if (id == levels.length) {
						levels = Arrays.copyOf(levels, id * 2);
						uvs = Arrays.copyOf(uvs, id * 12);
					}
					System.arraycopy(t, 0, uvs, id * 6, 6);
					// About a texel per micro-triangle along the longest edge.
					float longest = 0;
					for (int e = 0; e < 3; e++) {
						int a = e * 2, b = (e + 1) % 3 * 2;
						longest = Math.max(longest, (float) Math.hypot((t[a] - t[b]) * aw, (t[a + 1] - t[b + 1]) * ah));
					}
					int level = known ? (int) Math.ceil(Math.log(Math.max(longest, 1)) / Math.log(2)) : 4;
					levels[id] = (byte) Math.clamp(level, 1, MAX_LEVEL);
				}
				triangleEntry[q * 2 + half] = id;
			}
		}
		int n = ids.size();
		return new Plan(triangleEntry, Arrays.copyOf(uvs, n * 6), Arrays.copyOf(levels, n));
	}

	private record Key(int a, int b, int c, int d, int e, int f) {
		Key(float[] t) {
			this(Float.floatToIntBits(t[0]), Float.floatToIntBits(t[1]), Float.floatToIntBits(t[2]),
				Float.floatToIntBits(t[3]), Float.floatToIntBits(t[4]), Float.floatToIntBits(t[5]));
		}
	}

	/** 32-bit words of 2-bit states for an entry at this level (at least one). */
	private static int words(int level) {
		return Math.max(1, (1 << (2 * level)) / 16);
	}

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
			ByteBuffer spv = Shaders.compile("omm.comp");
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
	 * Records the opacity pass and micromap builds for the given layers' plans. Ends with a barrier, so BLAS
	 * builds recorded next can reference the results.
	 */
	static Micromap[] build(VkDevice device, VkCommandBuffer cmd, int slot, Plan[] plans, MemoryStack s) throws IOException {
		if (pipeline == 0) init(device);
		int n = plans.length;
		long[] dataOffset = new long[n], descOffset = new long[n], entryOffset = new long[n];
		int[] wordCount = new int[n];
		long total = 0, entryTotal = 0;
		for (int i = 0; i < n; i++) {
			int w = 0;
			for (byte level : plans[i].levels) w += words(level);
			wordCount[i] = w;
			dataOffset[i] = total;
			total = align(total + w * 4L, 256);
			descOffset[i] = total;
			total = align(total + (long) plans[i].entries() * TRIANGLE_DESC_SIZE, 256);
			entryOffset[i] = entryTotal;
			entryTotal = align(entryTotal + (long) plans[i].entries() * ENTRY_SIZE, 256);
		}
		Buf input = inputs[slot];
		if (input == null || input.size() < total) {
			if (input != null) input.free(); // this slot's last use was RING frames ago
			input = inputs[slot] = Memory.buffer(Math.max(total * 3 / 2, 1 << 20),
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_MICROMAP_BUILD_INPUT_READ_ONLY_BIT_EXT, false);
		}
		Buf entries = entryBufs[slot];
		if (entries == null || entries.size() < entryTotal) {
			if (entries != null) entries.free();
			entries = entryBufs[slot] = Memory.buffer(Math.max(entryTotal * 3 / 2, 1 << 20), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		// Entries: the UV triangle, its level and its first word.
		for (int i = 0; i < n; i++) {
			Plan p = plans[i];
			long base = entries.mapped() + entryOffset[i];
			int word = 0;
			for (int e = 0; e < p.entries(); e++) {
				long at = base + (long) e * ENTRY_SIZE;
				for (int k = 0; k < 6; k++) memPutFloat(at + k * 4L, p.uvs[e * 6 + k]);
				memPutInt(at + 24, p.levels[e]);
				memPutInt(at + 28, word);
				word += words(p.levels[e]);
			}
		}

		// Opacity states from the atlas.
		VulkanGpuTextureView atlas = (VulkanGpuTextureView) Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, VkWriteDescriptorSet.calloc(1, s).sType$Default()
			.dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)));
		ByteBuffer pc = s.malloc(32);
		for (int i = 0; i < n; i++) {
			pc.putLong(0, entries.address() + entryOffset[i]).putLong(8, input.address() + dataOffset[i])
				.putLong(16, input.address() + descOffset[i]).putInt(24, plans[i].entries()).putInt(28, wordCount[i]);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
			vkCmdDispatch(cmd, (wordCount[i] + 63) / 64, 1, 1);
		}
		VulkanCommandEncoder.memoryBarrier(cmd, s);

		// Micromaps.
		Micromap[] result = new Micromap[n];
		VkMicromapBuildInfoEXT.Buffer infos = VkMicromapBuildInfoEXT.calloc(n);
		VkMicromapUsageEXT.Buffer usages = VkMicromapUsageEXT.calloc(n * (MAX_LEVEL + 1));
		try {
			VkMicromapBuildSizesInfoEXT sizes = VkMicromapBuildSizesInfoEXT.calloc(s).sType$Default();
			long[] scratchOffset = new long[n];
			long scratchTotal = 0;
			LongBuffer h = s.mallocLong(1);
			for (int i = 0; i < n; i++) {
				Plan p = plans[i];
				int[] entriesPerLevel = new int[MAX_LEVEL + 1], trianglesPerLevel = new int[MAX_LEVEL + 1];
				for (byte level : p.levels) entriesPerLevel[level]++;
				for (int e : p.triangleEntry) trianglesPerLevel[p.levels[e]]++;
				int used = 0;
				for (int l = 0; l <= MAX_LEVEL; l++) {
					if (entriesPerLevel[l] == 0) continue;
					usages.get(i * (MAX_LEVEL + 1) + used++).count(entriesPerLevel[l]).subdivisionLevel(l).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
				}
				VkMicromapBuildInfoEXT info = infos.get(i).sType$Default().type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT)
					.flags(VK_BUILD_MICROMAP_PREFER_FAST_TRACE_BIT_EXT).mode(VK_BUILD_MICROMAP_MODE_BUILD_EXT)
					.pUsageCounts(usages.slice(i * (MAX_LEVEL + 1), used)).usageCountsCount(used) // shared count with ppUsageCounts: set by hand
					.triangleArrayStride(TRIANGLE_DESC_SIZE);
				info.data().deviceAddress(input.address() + dataOffset[i]);
				info.triangleArray().deviceAddress(input.address() + descOffset[i]);
				vkGetMicromapBuildSizesEXT(vk, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, sizes);
				Buf storage = Memory.buffer(sizes.micromapSize(), VK_BUFFER_USAGE_MICROMAP_STORAGE_BIT_EXT, false);
				check(vkCreateMicromapEXT(vk, VkMicromapCreateInfoEXT.calloc(s).sType$Default().buffer(storage.buffer()).offset(storage.offset())
					.size(sizes.micromapSize()).type(VK_MICROMAP_TYPE_OPACITY_MICROMAP_EXT), null, h), "create micromap");
				// The BLAS build reads which entry each triangle uses.
				Buf index = Memory.buffer(p.triangleEntry.length * 4L, KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true);
				memIntBuffer(index.mapped(), p.triangleEntry.length).put(p.triangleEntry);
				result[i] = new Micromap(storage, h.get(0), index, trianglesPerLevel);
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

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	static void destroy() {
		for (Buf b : inputs) if (b != null) b.free();
		for (Buf b : entryBufs) if (b != null) b.free();
		if (scratch != null) scratch.free();
		if (pipeline == 0) return;
		vkDestroyPipeline(vk, pipeline, null);
		vkDestroyPipelineLayout(vk, pipelineLayout, null);
		vkDestroyDescriptorSetLayout(vk, setLayout, null);
		vkDestroySampler(vk, sampler, null);
	}
}
