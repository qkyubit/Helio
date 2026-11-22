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
 */
public final class LodRenderer {
	/** LOD distance in blocks (800 chunks: the user's target). */
	public static final int DISTANCE = 800 * 16;
	/** A tile closer than this many times its size is drawn at the level below. */
	private static final float SPLIT = 6f;
	private static final int MAX_TILES = 16384, RING = 3, TILE_BYTES = 32;

	private static final class GpuTile {
		Memory.Buf quads;
		/** Quads per group (0: all but water, 1: water) and direction: [group * 6 + direction], ends at [12]. */
		final int[] dirStart = new int[13];
		long usedFrame;
	}

	private static final Long2ObjectOpenHashMap<GpuTile> tiles = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet requested = new LongOpenHashSet();
	private static long frame;
	/** pipelines[0]: tiles wholly past vanilla's terrain (no tests in the fragment shader, early depth test); [1]: the rest. */
	private static final long[] pipelines = new long[2];
	private static long layout, setLayout, sampler;
	private static Memory.Buf indices;
	private static final int INDEXED_QUADS = 32768, CMD_BYTES = 20;
	private static final Memory.Buf[] tileInfo = new Memory.Buf[RING];
	/** Indirect draws: [group * 2 + edge][ring slot]; group 0 all but water, 1 water. */
	private static final Memory.Buf[][] cmdBuffers = new Memory.Buf[4][RING];
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
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, s);
			blend.get(0).colorWriteMask(0xF);
			for (int edge = 0; edge < 2; edge++) {
			stages.get(1).pSpecializationInfo(VkSpecializationInfo.calloc(s).pMapEntries(VkSpecializationMapEntry.calloc(1, s).constantID(0).offset(0).size(4))
				.pData(s.malloc(4).putInt(0, edge)));
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
			pipelines[edge] = h.get(0);
			}
			// Every quad's two triangles: 0 1 2, 0 2 3 of its 4 vertices; draws offset their vertices to their quads.
			indices = Memory.buffer((long) INDEXED_QUADS * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, true);
			java.nio.IntBuffer ib = memIntBuffer(indices.mapped(), INDEXED_QUADS * 6);
			for (int q = 0; q < INDEXED_QUADS; q++) ib.put(q * 4).put(q * 4 + 1).put(q * 4 + 2).put(q * 4).put(q * 4 + 2).put(q * 4 + 3);
			vkDestroyShaderModule(vk, vm, null);
			vkDestroyShaderModule(vk, fm, null);
			for (int i = 0; i < RING; i++) {
				tileInfo[i] = Memory.buffer((long) MAX_TILES * TILE_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
				for (int c = 0; c < 4; c++) cmdBuffers[c][i] = Memory.buffer((long) MAX_TILES * 12 * CMD_BYTES, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, true);
			}
		}
	}

	/** Meshes the worker finished: onto the GPU (or dropped, if they changed meanwhile). Tiles that changed: re-meshed. */
	private static void receive() {
		for (long key; (key = Lod.pollDirtyMesh()) != Long.MIN_VALUE; ) {
			GpuTile g = tiles.remove(key);
			if (g != null && g.quads != null) {
				Memory.Buf b = g.quads;
				bytes -= b.size();
				dev.helio.rt.Scene.defer(b::free);
			}
			requested.remove(key);
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
					while (i < n && q[i * 4 + 3] == grp && (q[i * 4 + 1] & 7) == d) i++;
				}
				g.dirStart[12] = n;
			}
			g.usedFrame = frame;
			tiles.put(r.key(), g);
		}
	}

	/**
	 * Records the LOD pass. viewProj: the jittered projection times the view rotation (camera-relative);
	 * vanillaDepth: a copy of vanilla's depth from before this pass; colour, depth: vanilla's attachments.
	 */
	public static void draw(VkCommandBuffer cmd, VkDevice vk, Vec3 camera, Matrix4f viewProj, long vanillaDepth, long colour, long depth,
		int width, int height, float[] vanillaSky, float renderDistance) {
		if (!active()) return;
		if (session != Lod.store) { // another world: what's on the GPU is the last one's
			session = Lod.store;
			for (GpuTile g : tiles.values()) if (g.quads != null) {
				Memory.Buf b = g.quads;
				bytes -= b.size();
				dev.helio.rt.Scene.defer(b::free);
			}
			tiles.clear();
			requested.clear();
		}
		try {
			if (pipelines[0] == 0) init(vk);
			frame++;
			receive();
			int slot = (int) (frame % RING);
			long info = tileInfo[slot].mapped();
			long[] cmds = {cmdBuffers[0][slot].mapped(), cmdBuffers[1][slot].mapped(), cmdBuffers[2][slot].mapped(), cmdBuffers[3][slot].mapped()};
			int[] counts = new int[5]; // tiles, then commands per [group * 2 + edge]
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
						if (e.getValue().quads != null) {
							Memory.Buf b = e.getValue().quads;
							bytes -= b.size();
							dev.helio.rt.Scene.defer(b::free);
						}
						it.remove();
					}
				}
			}
			last = new Pass(slot, counts, new Matrix4f(viewProj), vanillaDepth, colour, depth, width, height, vanillaSky.clone(), renderDistance);
			record(cmd, 0);
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
	 * Records LOD water (group 1) chosen in this frame's draw: after the opaque depth was copied, so the composite
	 * finds it over the terrain as it finds vanilla's water, and gives it its reflection.
	 */
	public static void drawWater(VkCommandBuffer cmd) {
		if (!active() || last == null) return;
		try {
			record(cmd, 1);
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("Helio LODs failed; off for the rest of this session", t);
		} finally {
			last = null;
		}
	}

	/** group 0: everything but water; 1: water. */
	private static void record(VkCommandBuffer cmd, int group) {
		Pass p = last;
		int slot = p.slot(), width = p.width(), height = p.height();
		int[] counts = p.counts();
		if (counts[1 + group * 2] + counts[2 + group * 2] == 0) return;
		long vanillaDepth = p.vanillaDepth(), colour = p.colour(), depth = p.depth();
		Matrix4f viewProj = p.viewProj();
		float[] vanillaSky = p.vanillaSky();
		float renderDistance = p.renderDistance();
		try (MemoryStack s = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			VkRenderingAttachmentInfo.Buffer ca = VkRenderingAttachmentInfo.calloc(1, s);
			ca.get(0).sType$Default().imageView(colour).imageLayout(VK_IMAGE_LAYOUT_GENERAL).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			VkRenderingAttachmentInfo da = VkRenderingAttachmentInfo.calloc(s).sType$Default().imageView(depth).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
				.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			VkRenderingInfo ri = VkRenderingInfo.calloc(s).sType$Default().layerCount(1).pColorAttachments(ca).pDepthAttachment(da);
			ri.renderArea().extent().set(width, height);
			if (group == 0) dev.helio.vk.GpuTimers.begin(cmd, dev.helio.vk.GpuTimers.Pass.LOD); // (the opaque pass: most of the cost)
			vkCmdBeginRenderingKHR(cmd, ri);
			vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, s).width(width).height(height).minDepth(0).maxDepth(1));
			VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
			sc.get(0).extent().set(width, height);
			vkCmdSetScissor(cmd, 0, sc);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(vanillaDepth).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines[1]); // (push descriptors need a bound layout only)
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, w);
			ByteBuffer pc = s.malloc(96);
			viewProj.get(0, pc);
			pc.putLong(64, tileInfo[slot].address());
			// No LODs where vanilla's terrain is sure to be: a section short of its render distance.
			pc.putFloat(72, vanillaSky[0]).putFloat(76, vanillaSky[1]).putFloat(80, vanillaSky[2]).putFloat(84, Math.max(0, renderDistance - 16));
			vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc);
			vkCmdBindIndexBuffer(cmd, indices.buffer(), indices.offset(), VK_INDEX_TYPE_UINT32);
			for (int edge = 0; edge < 2; edge++) {
				if (counts[1 + group * 2 + edge] == 0) continue;
				vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelines[edge]);
				Memory.Buf b = cmdBuffers[group * 2 + edge][slot];
				vkCmdDrawIndexedIndirect(cmd, b.buffer(), b.offset(), counts[1 + group * 2 + edge], CMD_BYTES);
			}
			vkCmdEndRenderingKHR(cmd);
			if (group == 0) dev.helio.vk.GpuTimers.end(cmd, dev.helio.vk.GpuTimers.Pass.LOD);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		}
	}

	/** One tile of the octree: drawn, split into its children, or skipped. */
	private static void select(int level, int tx, int ty, int tz, Vec3 c, float vanillaDistance, long info, long[] cmds, int[] counts) {
		int size = Lod.TILE << level;
		double x0 = (double) tx * size, y0 = (double) ty * size, z0 = (double) tz * size;
		double dx = Math.max(0, Math.max(x0 - c.x, c.x - (x0 + size))), dy = Math.max(0, Math.max(y0 - c.y, c.y - (y0 + size))),
			dz = Math.max(0, Math.max(z0 - c.z, c.z - (z0 + size)));
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > DISTANCE) return;
		// Wholly inside vanilla's terrain (its farthest corner within the render distance): nothing to add.
		double fx = Math.max(Math.abs(x0 - c.x), Math.abs(x0 + size - c.x)), fz = Math.max(Math.abs(z0 - c.z), Math.abs(z0 + size - c.z));
		if (fx * fx + fz * fz < (double) (vanillaDistance - 16) * (vanillaDistance - 16)) return;
		if (level > 1 && Math.sqrt(horizontal * horizontal + dy * dy) < SPLIT * size) {
			for (int k = 0; k < 8; k++) select(level - 1, tx * 2 + (k & 1), ty * 2 + (k >> 2), tz * 2 + (k >> 1 & 1), c, vanillaDistance, info, cmds, counts);
			return;
		}
		long key = LodStore.key(level, tx, ty, tz);
		GpuTile g = tiles.get(key);
		if (g == null) {
			if (requested.add(key)) Lod.requestMesh(key);
			return;
		}
		g.usedFrame = frame;
		if (g.quads == null || counts[0] >= MAX_TILES) return;
		int t = counts[0]++;
		long p = info + (long) t * TILE_BYTES;
		float voxel = 1 << level;
		memPutFloat(p, (float) (x0 - c.x));
		memPutFloat(p + 4, (float) (y0 - c.y));
		memPutFloat(p + 8, (float) (z0 - c.z));
		memPutFloat(p + 12, voxel);
		memPutLong(p + 16, g.quads.address());
		// Directions that can face the camera: down unless it's below the tile's bottom, and so on.
		boolean[] faces = {c.y < y0 + size, c.y > y0, c.z < z0 + size, c.z > z0, c.x < x0 + size, c.x > x0};
		// Past vanilla's terrain altogether (a section's margin): the pipeline without fragment tests.
		int edge = horizontal > vanillaDistance + 16 ? 0 : 1;
		for (int grp = 0; grp < 2; grp++) for (int d = 0; d < 6; d++) {
			if (!faces[d]) continue;
			for (int start = g.dirStart[grp * 6 + d], end = g.dirStart[grp * 6 + d + 1]; start < end; start += INDEXED_QUADS) {
				int n = Math.min(INDEXED_QUADS, end - start), list = grp * 2 + edge;
				long q = cmds[list] + (long) counts[1 + list]++ * CMD_BYTES;
				memPutInt(q, n * 6);
				memPutInt(q + 4, 1);
				memPutInt(q + 8, 0);
				memPutInt(q + 12, start * 4); // vertexOffset: the quad's first vertex
				memPutInt(q + 16, t);
				drawnQuads += n;
			}
		}
	}
}
