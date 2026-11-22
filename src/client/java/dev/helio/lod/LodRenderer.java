package dev.helio.lod;

import dev.helio.Helio;
import dev.helio.vk.Memory;
import dev.helio.vk.Shaders;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Draws the LOD tiles (M8 step 10c) into vanilla's colour and depth, as vanilla's raster would (its colour
 * convention, the frame's jitter), before Helio's composite, which then shades them like the rest of the picture
 * past the RT distance. Render thread.
 * <p>
 * Selection: an octree from level-{@value Lod#MAX_LEVEL} tiles out to the LOD distance; a tile closer than
 * {@link #SPLIT} times its own size is drawn as its 8 children instead, down to level 1. Per tile only the
 * directions that can face the camera are drawn: one indirect draw each, the tile by instance index.
 * <p>
 * Occlusion, on the GPU in two phases (lod_cull.comp): the candidate tiles visible last frame are drawn first; a
 * depth pyramid is built from that and vanilla's terrain (lod_hiz.comp); every candidate is then tested against
 * it, the result kept for the next frame, and those visible but not yet drawn are drawn. Testing against this
 * frame's depth, nothing pops in as the view turns.
 */
public final class LodRenderer {
	/** LOD distance in blocks (800 chunks: the user's target). */
	public static final int DISTANCE = 800 * 16;
	/** A tile closer than this many times its size is drawn at the level below. */
	private static final float SPLIT_DEFAULT = 5f; // 6: 1.09 ms over the pregen world, 5: 0.95 ms and nearly the same look, 4: 0.78 ms, visibly coarser
	/** Bench word "split:<x>" sets it (cost against detail). */
	public static float SPLIT = SPLIT_DEFAULT;
	/** Children are meshed ahead from this much farther than SPLIT. */
	private static final float PREFETCH = 1.4f;
	/** LODs are drawn (where vanilla has nothing) this far inside vanilla's render distance, blocks. */
	private static final int EDGE_BAND = 48;
	private static final int MAX_TILES = 16384, RING = 3, TILE_BYTES = 48, MAX_DRAWS = MAX_TILES * 12, MAX_SLOTS = 65536, LISTS = 6;

	private static final class GpuTile {
		Memory.Buf quads;
		/** Quads per group (0: all but water, 1: water) and direction: [group * 6 + direction], ends at [12]. */
		final int[] dirStart = new int[13];
		long usedFrame;
		/** Its place in the visibility buffer (lod_cull.comp). */
		int slot = -1;
		/** Its mesh's bounds in voxels, packed (lod_cull.comp tests these, not the tile: tiles are mostly air). */
		int boundsLo, boundsHi;
	}

	private static final it.unimi.dsi.fastutil.ints.IntArrayList freeSlots = new it.unimi.dsi.fastutil.ints.IntArrayList();
	private static int nextSlot;

	private static int takeSlot() {
		return freeSlots.isEmpty() ? nextSlot < MAX_SLOTS ? nextSlot++ : 0 : freeSlots.popInt();
	}

	/** A tile leaves the GPU: its mesh freed once frames in flight are done with it, its slot reused. */
	private static void release(GpuTile g) {
		if (g.quads != null) {
			Memory.Buf b = g.quads;
			bytes -= b.size();
			dev.helio.rt.Scene.defer(b::free);
		}
		if (g.slot >= 0) freeSlots.add(g.slot);
	}

	private static final Long2ObjectOpenHashMap<GpuTile> tiles = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet requested = new LongOpenHashSet();
	private static long frame;
	/**
	 * pipelines[0]: tiles wholly past vanilla's terrain (no tests in the fragment shader, early depth test); [1]: the
	 * rest. [2], [3]: the same for water, blended over its bed by its alpha as vanilla's is.
	 */
	private static final long[] pipelines = new long[4];
	private static long layout, setLayout, sampler;
	private static Memory.Buf indices;
	private static final int INDEXED_QUADS = 32768, CMD_BYTES = 20;
	private static final Memory.Buf[] tileInfo = new Memory.Buf[RING], templates = new Memory.Buf[RING];
	/** The culled draws: lists of MAX_DRAWS, phase 1 [far, edge], phase 2 [far, edge], water [far, edge]; their counts; visibility per slot. */
	private static Memory.Buf lists, listCounts, visibility;
	/** listCounts copied back (one per ring slot), for the bench log: draws left after culling. */
	private static final Memory.Buf[] countsBack = new Memory.Buf[RING];
	public static int candidateDraws, culledDraws;
	private static long cullPipeline, cullLayout, cullSetLayout, hizPipeline, hizLayout, hizSetLayout;
	/** The depth pyramid's levels (level 0: half the depth's size). */
	private static Memory.Img[] pyramid = new Memory.Img[0];
	private static int pyramidWidth, pyramidHeight;
	private static boolean failed;
	public static int drawnTiles, drawnQuads;
	private static Object session;

	private LodRenderer() {}

	/** Bench word "nolod": no LOD pass (its cost, A/B). */
	public static boolean benchOff;
	private static long bytes;

	public static int gpuTiles() {
		return tiles.size();
	}

	public static long gpuBytes() {
		return bytes;
	}

	/** Whether LODs draw this frame (a store open, nothing failed). */
	public static boolean active() {
		return Lod.store != null && !failed && !benchOff;
	}

	private static void init(VkDevice vk) throws java.io.IOException {
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			ByteBuffer vert = Shaders.compile("lod.vert"), frag = Shaders.compile("lod.frag");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(vert), null, h), "lod.vert");
			long vm = h.get(0);
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(frag), null, h), "lod.frag");
			long fm = h.get(0);
			memFree(vert);
			memFree(frag);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "lod sampler");
			sampler = h.get(0);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "lod set layout");
			setLayout = h.get(0);
			VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s);
			pcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(96);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout)).pPushConstantRanges(pcr), null, h), "lod layout");
			layout = h.get(0);

			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, s);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(s.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(s.UTF8("main"));
			for (int k = 0; k < 4; k++) {
			int edge = k & 1;
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, s);
			blend.get(0).colorWriteMask(0xF);
			if (k >= 2) blend.get(0).blendEnable(true).colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
				.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
				.srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE);
			VkSpecializationMapEntry.Buffer constants = VkSpecializationMapEntry.calloc(2, s);
			constants.get(0).constantID(0).offset(0).size(4); // EDGE
			constants.get(1).constantID(1).offset(4).size(4); // WATER
			stages.get(1).pSpecializationInfo(VkSpecializationInfo.calloc(s).pMapEntries(constants).pData(s.malloc(8).putInt(0, edge).putInt(4, k >> 1)));
			VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, s);
			ci.get(0).sType$Default()
				.pNext(VkPipelineRenderingCreateInfo.calloc(s).sType$Default().colorAttachmentCount(1)
					.pColorAttachmentFormats(s.ints(VK_FORMAT_R8G8B8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT))
				.pStages(stages)
				.pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default())
				.pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
				.pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1))
				.pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
					.cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1))
				.pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
				.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(true).depthWriteEnable(true)
					.depthCompareOp(VK_COMPARE_OP_GREATER_OR_EQUAL)) // reversed Z
				.pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(blend))
				.pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default().pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
				.layout(layout);
			check(vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, ci, null, h), "lod pipeline");
			pipelines[k] = h.get(0);
			}
			// Every quad's two triangles: 0 1 2, 0 2 3 of its 4 vertices; draws offset their vertices to their quads.
			indices = Memory.buffer((long) INDEXED_QUADS * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, true);
			java.nio.IntBuffer ib = memIntBuffer(indices.mapped(), INDEXED_QUADS * 6);
			for (int q = 0; q < INDEXED_QUADS; q++) ib.put(q * 4).put(q * 4 + 1).put(q * 4 + 2).put(q * 4).put(q * 4 + 2).put(q * 4 + 3);
			vkDestroyShaderModule(vk, vm, null);
			vkDestroyShaderModule(vk, fm, null);
			for (int i = 0; i < RING; i++) {
				tileInfo[i] = Memory.buffer((long) MAX_TILES * TILE_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
				templates[i] = Memory.buffer((long) MAX_DRAWS * CMD_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
				countsBack[i] = Memory.buffer(LISTS * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
			}
			lists = Memory.buffer((long) LISTS * MAX_DRAWS * CMD_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, false);
			listCounts = Memory.buffer(LISTS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
			visibility = Memory.buffer(MAX_SLOTS * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
			// Culling: the pyramid's levels as a sampler array, the rest by address.
			VkDescriptorSetLayoutBinding.Buffer cb = VkDescriptorSetLayoutBinding.calloc(1, s);
			cb.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(cb), null, h), "lod cull set layout");
			cullSetLayout = h.get(0);
			VkPushConstantRange.Buffer cpr = VkPushConstantRange.calloc(1, s);
			cpr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(128);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(cullSetLayout)).pPushConstantRanges(cpr), null, h), "lod cull layout");
			cullLayout = h.get(0);
			cullPipeline = compute(vk, "lod_cull.comp", cullLayout, s);
			VkDescriptorSetLayoutBinding.Buffer hb = VkDescriptorSetLayoutBinding.calloc(3, s);
			hb.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			hb.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			hb.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(hb), null, h), "lod hiz set layout");
			hizSetLayout = h.get(0);
			VkPushConstantRange.Buffer hpr = VkPushConstantRange.calloc(1, s);
			hpr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(12);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(hizSetLayout)).pPushConstantRanges(hpr), null, h), "lod hiz layout");
			hizLayout = h.get(0);
			hizPipeline = compute(vk, "lod_hiz.comp", hizLayout, s);
		}
	}

	private static long compute(VkDevice vk, String shader, long layout, MemoryStack s) throws java.io.IOException {
		LongBuffer h = s.mallocLong(1);
		ByteBuffer code = Shaders.compile(shader);
		check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, h), shader);
		long module = h.get(0);
		memFree(code);
		VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s);
		ci.get(0).sType$Default().layout(layout).stage(VkPipelineShaderStageCreateInfo.calloc(s).sType$Default()
			.stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main")));
		check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "pipeline " + shader);
		vkDestroyShaderModule(vk, module, null);
		return h.get(0);
	}

	/** The depth pyramid for a depth of this size: levels down to 1x1, made (and moved to GENERAL) on a change. */
	private static void ensurePyramid(VkCommandBuffer cmd, int width, int height, MemoryStack s) {
		if (pyramidWidth == width && pyramidHeight == height) return;
		for (Memory.Img img : pyramid) {
			Memory.Img old = img;
			dev.helio.rt.Scene.defer(old::free);
		}
		java.util.List<Memory.Img> levels = new java.util.ArrayList<>();
		for (int w = width, hh = height; levels.size() < 16; ) {
			w = Math.max(1, (w + 1) / 2);
			hh = Math.max(1, (hh + 1) / 2);
			levels.add(Memory.storageImage(w, hh, VK_FORMAT_R32_SFLOAT));
			if (w == 1 && hh == 1) break;
		}
		pyramid = levels.toArray(new Memory.Img[0]);
		pyramidWidth = width;
		pyramidHeight = height;
		VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(pyramid.length, s);
		for (int i = 0; i < pyramid.length; i++) {
			b.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).image(pyramid[i].image())
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			b.get(i).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		}
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, b);
	}

	/**
	 * Meshes the worker finished: onto the GPU, in place of the tile's old mesh. Tiles that changed are meshed
	 * again, their old mesh drawn until then: dropping it at once left holes in bursts (a chunk the client
	 * dropped is ingested, and its tile, neighbours and every coarser tile over it changed).
	 */
	private static void receive() {
		for (long key; (key = Lod.pollDirtyMesh()) != Long.MIN_VALUE; ) {
			if (tiles.containsKey(key) && requested.add(key)) Lod.requestMesh(key);
		}
		for (Lod.MeshResult r; (r = Lod.pollMesh()) != null; ) {
			if (!requested.remove(r.key())) continue;
			GpuTile g = new GpuTile();
			int[] q = r.quads();
			if (q != null && q.length > 0) {
				g.quads = Memory.buffer((long) q.length * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
				bytes += (long) q.length * 4;
				memIntBuffer(g.quads.mapped(), q.length).put(q);
				// Quads come grouped by direction (LodMesher's outer loop).
				int n = q.length / 4, i = 0;
				for (int grp = 0; grp < 2; grp++) for (int d = 0; d < 6; d++) {
					g.dirStart[grp * 6 + d] = i;
					while (i < n && (q[i * 4 + 3] & 255) == grp && (q[i * 4 + 1] & 7) == d) i++;
				}
				g.dirStart[12] = n;
				int[] lo = {63, 63, 63}, hi = {0, 0, 0};
				for (int k = 0; k < n; k++) {
					int pos = q[k * 4], dir = q[k * 4 + 1] & 7;
					int[] at = {pos & 63, pos >> 6 & 63, pos >> 12 & 63}, end = at.clone();
					end[LodMesher.AXES[dir][2]] += pos >>> 18 & 63;
					end[LodMesher.AXES[dir][3]] += pos >>> 24 & 63;
					for (int a = 0; a < 3; a++) {
						lo[a] = Math.min(lo[a], at[a]);
						hi[a] = Math.max(hi[a], end[a]);
					}
				}
				g.boundsLo = lo[0] | lo[1] << 6 | lo[2] << 12 | hi[0] << 18 | hi[1] << 24;
				g.boundsHi = hi[2];
			}
			g.usedFrame = frame;
			GpuTile old = tiles.put(r.key(), g);
			if (old != null) {
				g.slot = old.slot; // the same tile: its visibility carries over
				old.slot = -1;
				release(old);
			} else g.slot = takeSlot();
		}
	}

	/**
	 * Records the LOD pass. viewProj: the jittered projection times the view rotation (camera-relative);
	 * vanillaDepth: a copy of vanilla's depth from before this pass; colour, depth: vanilla's attachments.
	 */
	public static void draw(VkCommandBuffer cmd, VkDevice vk, Vec3 camera, Matrix4f viewProj, long vanillaDepth, long colour, long depth,
		long depthView, int width, int height, float[] vanillaSky, float renderDistance) {
		if (!active()) return;
		if (session != Lod.store) { // another world: what's on the GPU is the last one's
			session = Lod.store;
			for (GpuTile g : tiles.values()) release(g);
			tiles.clear();
			requested.clear();
		}
		try {
			if (pipelines[0] == 0) init(vk);
			frame++;
			receive();
			int slot = (int) (frame % RING);
			long info = tileInfo[slot].mapped();
			long[] cmds = {templates[slot].mapped()};
			int[] counts = new int[2]; // tiles, draws
			int top = Lod.MAX_LEVEL, size = Lod.TILE << top;
			int r = DISTANCE / size + 1, cx = (int) Math.floor(camera.x / size), cz = (int) Math.floor(camera.z / size);
			drawnQuads = 0;
			for (int tz = cz - r; tz <= cz + r; tz++) for (int tx = cx - r; tx <= cx + r; tx++)
				for (int ty = -1; ty <= 0; ty++) select(top, tx, ty, tz, camera, renderDistance, info, cmds, counts);
			drawnTiles = counts[0];
			// Stale tiles: off the GPU after a while unseen.
			if (frame % 120 == 0) {
				var it = tiles.long2ObjectEntrySet().fastIterator();
				while (it.hasNext()) {
					var e = it.next();
					if (frame - e.getValue().usedFrame > 600) {
						release(e.getValue());
						it.remove();
					}
				}
			}
			last = new Pass(slot, counts, new Matrix4f(viewProj), vanillaDepth, colour, depth, width, height, vanillaSky.clone(), renderDistance);
			if (counts[0] == 0) return;
			try (MemoryStack s = MemoryStack.stackPush()) {
				ensurePyramid(cmd, width, height, s);
				if (frame == 1) vkCmdFillBuffer(cmd, visibility.buffer(), visibility.offset(), MAX_SLOTS * 4L, 0);
				dev.helio.vk.GpuTimers.begin(cmd, dev.helio.vk.GpuTimers.Pass.LOD);
				vkCmdFillBuffer(cmd, listCounts.buffer(), listCounts.offset(), LISTS * 4, 0);
				VulkanCommandEncoder.memoryBarrier(cmd, s);
				cull(cmd, 1, s);
				record(cmd, 0, s); // phase 1: visible last frame
				buildPyramid(cmd, depthView, s);
				cull(cmd, 2, s);
				record(cmd, 1, s); // phase 2: newly visible
				dev.helio.vk.GpuTimers.end(cmd, dev.helio.vk.GpuTimers.Pass.LOD);
				// What culling left, read back RING frames later.
				java.nio.IntBuffer back = memIntBuffer(countsBack[slot].mapped(), LISTS);
				culledDraws = back.get(0) + back.get(1) + back.get(2) + back.get(3);
				candidateDraws = counts[1];
				vkCmdCopyBuffer(cmd, listCounts.buffer(), countsBack[slot].buffer(),
					VkBufferCopy.calloc(1, s).srcOffset(listCounts.offset()).dstOffset(countsBack[slot].offset()).size(LISTS * 4));
				VulkanCommandEncoder.memoryBarrier(cmd, s);
			}
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Helio LODs failed; off for the rest of this session", t);
		}
	}


	/** This frame's selection and targets, for its two passes. */
	private record Pass(int slot, int[] counts, Matrix4f viewProj, long vanillaDepth, long colour, long depth, int width, int height,
		float[] vanillaSky, float renderDistance) {}

	private static Pass last;

	/**
	 * Records LOD water, those of this frame's candidates phase 2 found visible: after the opaque depth was copied,
	 * so the composite finds it over the terrain as it finds vanilla's water, and gives it its reflection.
	 */
	public static void drawWater(VkCommandBuffer cmd) {
		if (!active() || last == null || last.counts()[0] == 0) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			record(cmd, 2, s);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Helio LODs failed; off for the rest of this session", t);
		} finally {
			last = null;
		}
	}

	/** A culling phase (lod_cull.comp): 1 emits last frame's visible tiles, 2 tests them all. */
	private static void cull(VkCommandBuffer cmd, int phase, MemoryStack s) {
		Pass p = last;
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cullPipeline);
		VkDescriptorImageInfo.Buffer levels = VkDescriptorImageInfo.calloc(16, s);
		for (int i = 0; i < 16; i++) levels.get(i).sampler(sampler).imageView(pyramid[Math.min(i, pyramid.length - 1)].view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
		VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s);
		w.get(0).sType$Default().dstBinding(0).descriptorCount(16).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(levels);
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cullLayout, 0, w);
		ByteBuffer pc = s.malloc(128);
		p.viewProj().get(0, pc);
		pc.putLong(64, tileInfo[p.slot()].address()).putLong(72, templates[p.slot()].address()).putLong(80, visibility.address())
			.putLong(88, lists.address()).putLong(96, listCounts.address())
			.putInt(104, p.counts()[0]).putInt(108, phase).putInt(112, pyramid.length).putInt(116, MAX_DRAWS)
			.putFloat(120, p.width()).putFloat(124, p.height());
		vkCmdPushConstants(cmd, cullLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
		vkCmdDispatch(cmd, (p.counts()[0] + 63) / 64, 1, 1);
		VulkanCommandEncoder.memoryBarrier(cmd, s);
	}

	/** The depth pyramid from vanilla's depth as it stands (its terrain, and the LODs drawn so far). */
	private static void buildPyramid(VkCommandBuffer cmd, long depthView, MemoryStack s) {
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, hizPipeline);
		int bw = last.width(), bh = last.height();
		for (int i = 0; i < pyramid.length; i++) {
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(3, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(pyramid[Math.max(i - 1, 0)].view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(2).sType$Default().dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(pyramid[i].view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, hizLayout, 0, w);
			vkCmdPushConstants(cmd, hizLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, s.ints(bw, bh, i == 0 ? 1 : 0));
			vkCmdDispatch(cmd, (pyramid[i].width() + 7) / 8, (pyramid[i].height() + 7) / 8, 1);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			bw = pyramid[i].width();
			bh = pyramid[i].height();
		}
	}

	/** Draws a pair of culled lists (list: 0 phase 1, 1 phase 2, 2 water; each far then edge). */
	private static void record(VkCommandBuffer cmd, int list, MemoryStack s) {
		Pass p = last;
		int slot = p.slot(), width = p.width(), height = p.height();
		float[] vanillaSky = p.vanillaSky();
		VulkanCommandEncoder.memoryBarrier(cmd, s);
		VkRenderingAttachmentInfo.Buffer ca = VkRenderingAttachmentInfo.calloc(1, s);
		ca.get(0).sType$Default().imageView(p.colour()).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
		VkRenderingAttachmentInfo da = VkRenderingAttachmentInfo.calloc(s).sType$Default().imageView(p.depth()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
			.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
		VkRenderingInfo ri = VkRenderingInfo.calloc(s).sType$Default().layerCount(1).pColorAttachments(ca).pDepthAttachment(da);
		ri.renderArea().extent().set(width, height);
		vkCmdBeginRenderingKHR(cmd, ri);
		vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, s).width(width).height(height).minDepth(0).maxDepth(1));
		VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
		sc.get(0).extent().set(width, height);
		vkCmdSetScissor(cmd, 0, sc);
		VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s);
		w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
			.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(p.vanillaDepth()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines[1]);
		vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, w);
		ByteBuffer pc = s.malloc(96);
		p.viewProj().get(0, pc);
		pc.putLong(64, tileInfo[slot].address());
		// No LODs where vanilla's terrain is sure to be: short of its render distance by EDGE_BAND.
		pc.putFloat(72, vanillaSky[0]).putFloat(76, vanillaSky[1]).putFloat(80, vanillaSky[2]).putFloat(84, Math.max(0, p.renderDistance() - EDGE_BAND));
		vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
		vkCmdBindIndexBuffer(cmd, indices.buffer(), indices.offset(), VK_INDEX_TYPE_UINT32);
		for (int edge = 0; edge < 2; edge++) {
			int l = list * 2 + edge;
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines[(list == 2 ? 2 : 0) + edge]);
			vkCmdDrawIndexedIndirectCount(cmd, lists.buffer(), lists.offset() + (long) l * MAX_DRAWS * CMD_BYTES,
				listCounts.buffer(), listCounts.offset() + l * 4L, MAX_DRAWS, CMD_BYTES);
		}
		vkCmdEndRenderingKHR(cmd);
		VulkanCommandEncoder.memoryBarrier(cmd, s);
	}

	/** One tile of the octree: drawn, split into its children, or skipped. */
	private static void select(int level, int tx, int ty, int tz, Vec3 c, float vanillaDistance, long info, long[] cmds, int[] counts) {
		int size = Lod.TILE << level;
		double x0 = (double) tx * size, y0 = (double) ty * size, z0 = (double) tz * size;
		double dx = Math.max(0, Math.max(x0 - c.x, c.x - (x0 + size))), dy = Math.max(0, Math.max(y0 - c.y, c.y - (y0 + size))),
			dz = Math.max(0, Math.max(z0 - c.z, c.z - (z0 + size)));
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > DISTANCE) return;
		// Wholly inside vanilla's terrain, short of its edge band (its farthest corner within the render distance
		// less EDGE_BAND): nothing to add. In the band LODs fill wherever vanilla has nothing (chunks it dropped as
		// the player moved away, before the LOD tile was ready: gaps that came in bursts walking backwards).
		// Vanilla's terrain is a cylinder: from high above (y 400) it drew nothing below, and LODs left a hole.
		double fx = Math.max(Math.abs(x0 - c.x), Math.abs(x0 + size - c.x)), fz = Math.max(Math.abs(z0 - c.z), Math.abs(z0 + size - c.z)),
			fy = Math.max(Math.abs(y0 - c.y), Math.abs(y0 + size - c.y));
		double inner = Math.max(0, vanillaDistance - EDGE_BAND);
		if (fx * fx + fz * fz < inner * inner && fy < inner) return;
		double distance = Math.sqrt(horizontal * horizontal + dy * dy);
		if (level > 1 && distance < SPLIT * size) {
			for (int k = 0; k < 8; k++) select(level - 1, tx * 2 + (k & 1), ty * 2 + (k >> 2), tz * 2 + (k >> 1 & 1), c, vanillaDistance, info, cmds, counts);
			return;
		}
		long key = LodStore.key(level, tx, ty, tz);
		GpuTile g = tiles.get(key);
		if (g == null) {
			if (requested.add(key)) Lod.requestMesh(key);
			// Until it's ready, its children if they're there (moving away: the finer tiles drawn till now).
			if (level > 1) for (int k = 0; k < 8; k++) {
				int ctx = tx * 2 + (k & 1), cty = ty * 2 + (k >> 2), ctz = tz * 2 + (k >> 1 & 1);
				GpuTile child = tiles.get(LodStore.key(level - 1, ctx, cty, ctz));
				if (child != null) emit(child, level - 1, ctx, cty, ctz, c, vanillaDistance, info, cmds, counts);
			}
			return;
		}
		// Coming closer: its children asked for ahead of time, so they're ready when it splits.
		if (level > 1 && distance < SPLIT * PREFETCH * size) for (int k = 0; k < 8; k++) {
			long ck = LodStore.key(level - 1, tx * 2 + (k & 1), ty * 2 + (k >> 2), tz * 2 + (k >> 1 & 1));
			if (!tiles.containsKey(ck) && requested.add(ck)) Lod.requestMesh(ck);
		}
		emit(g, level, tx, ty, tz, c, vanillaDistance, info, cmds, counts);
	}

	/** One tile's draws (the directions that can face the camera). */
	private static void emit(GpuTile g, int level, int tx, int ty, int tz, Vec3 c, float vanillaDistance, long info, long[] cmds, int[] counts) {
		int size = Lod.TILE << level;
		double x0 = (double) tx * size, y0 = (double) ty * size, z0 = (double) tz * size;
		double dx = Math.max(0, Math.max(x0 - c.x, c.x - (x0 + size))), dz = Math.max(0, Math.max(z0 - c.z, c.z - (z0 + size)));
		double horizontal = Math.sqrt(dx * dx + dz * dz), dy = Math.max(0, Math.max(y0 - c.y, c.y - (y0 + size)));
		g.usedFrame = frame;
		if (g.quads == null || counts[0] >= MAX_TILES || counts[1] + 24 > MAX_DRAWS) return;
		int t = counts[0]++;
		long p = info + (long) t * TILE_BYTES;
		float voxel = 1 << level;
		memPutFloat(p, (float) (x0 - c.x));
		memPutFloat(p + 4, (float) (y0 - c.y));
		memPutFloat(p + 8, (float) (z0 - c.z));
		memPutFloat(p + 12, voxel);
		memPutLong(p + 16, g.quads.address());
		memPutInt(p + 24, g.slot);
		memPutInt(p + 28, counts[1]); // its draws start here in the templates
		// Directions that can face the camera: down unless it's below the tile's bottom, and so on.
		boolean[] faces = {c.y < y0 + size, c.y > y0, c.z < z0 + size, c.z > z0, c.x < x0 + size, c.x > x0};
		// Past vanilla's terrain altogether (a section's margin): the pipeline without fragment tests.
		int edge = Math.max(horizontal, dy) > vanillaDistance + 16 ? 0 : 1;
		memPutInt(p + 40, edge | g.boundsHi << 8);
		memPutInt(p + 44, g.boundsLo);
		for (int grp = 0; grp < 2; grp++) {
			int first = counts[1];
			for (int d = 0; d < 6; d++) {
			if (!faces[d]) continue;
			for (int start = g.dirStart[grp * 6 + d], end = g.dirStart[grp * 6 + d + 1]; start < end; start += INDEXED_QUADS) {
				int n = Math.min(INDEXED_QUADS, end - start);
				long q = cmds[0] + (long) counts[1]++ * CMD_BYTES;
				memPutInt(q, n * 6);
				memPutInt(q + 4, 1);
				memPutInt(q + 8, 0);
				memPutInt(q + 12, start * 4); // vertexOffset: the quad's first vertex
				memPutInt(q + 16, t);
				drawnQuads += n;
			}
			}
			memPutInt(p + 32 + grp * 4, counts[1] - first); // its opaque, then water, draws
		}
	}
}
