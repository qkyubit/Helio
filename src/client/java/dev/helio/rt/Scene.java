package dev.helio.rt;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.ViewAreaAccessor;
import dev.helio.vk.GpuTimers;
import dev.helio.vk.Memory;
import dev.helio.vk.Memory.Buf;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static dev.helio.vk.Memory.check;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The ray-tracing scene: one BLAS per chunk-section layer and a TLAS rebuilt every frame.
 * <p>
 * Geometry is the mesh vanilla's section compiler already makes, so every block model, fluid and 3D
 * resource pack is included, and block updates arrive as recompiles. Compile worker threads copy each
 * layer's vertices into a buffer of ours ({@link #submit}); the render thread builds BLASes from the
 * queue within a per-frame budget, compacts them a few frames later, and builds the TLAS from every
 * section within the RT distance, visible or not (shadows and reflections need off-screen geometry).
 * <p>
 * Vertices stay resident after the build: hit shaders read UVs from them through
 * {@link #instanceInfo} (one vertex-buffer address per TLAS instance).
 */
public final class Scene {
	/** Instance mask per layer: solid geometry is opaque, cutout needs an alpha test, translucent is skipped by shadow rays. */
	public static final int MASK_SOLID = 1, MASK_CUTOUT = 2, MASK_TRANSLUCENT = 4, MASK_DYNAMIC = 8;
	/** Also on translucent layers holding more than water (stained glass, ice, nether portals): reflections see those. */
	public static final int MASK_NOT_ONLY_WATER = 16;
	private static final int[] LAYER_MASK = {MASK_SOLID, MASK_CUTOUT, MASK_TRANSLUCENT};
	/** Vanilla's BLOCK format: position float3, colour 4 bytes, UV0 float2, lightmap 2 shorts. Shaders hard-code it. */
	public static final int VERTEX_STRIDE = 28;
	private static final int INFO_SIZE = 16, KIND_DYNAMIC = 3;
	/** Frames until the GPU is surely done with a frame's commands (Mojang keeps at most two in flight). */
	private static final int RING = 4;
	// ponytail: fixed per-frame build budget; scale it by measured GPU time if world loads drag.
	private static final int MAX_BUILDS = 256, MAX_TRIANGLES = 256 * 1024;
	private static final int BUILD_INPUT = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

	private static final class Blas {
		Buf storage;
		long handle, address;
		boolean dead;
		/** Cutout layers only; outlives compaction (the compacted copy references it too). */
		Micromaps.Micromap omm;
	}

	/** ommPlan: cutout layers with micromaps on, worked out on the compile worker (Micromaps.plan). */
	private record Geo(int layer, int mask, Buf vertices, int quads, Blas blas, Micromaps.Plan ommPlan) {}

	/** layers null: remove the section. lights: Lights.collect output, null if none. */
	private record Upload(long node, Geo[] layers, int[] lights) {}

	private record Deferred(long frame, Runnable free) {}

	private static volatile boolean ready;
	private static VkDevice vk;
	private static long scratchAlignment;
	private static boolean warnedFormat;

	private static final ConcurrentLinkedQueue<Upload> incoming = new ConcurrentLinkedQueue<>();
	/** Uploads waiting for a build slot, oldest first; a newer upload of the same section replaces its entry. */
	private static final Long2ObjectLinkedOpenHashMap<Geo[]> pending = new Long2ObjectLinkedOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Geo[]> sections = new Long2ObjectOpenHashMap<>();
	private static final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
	private static long frame;

	private static Buf scratch, quadIndices, tlasStorage, tlasScratch;
	private static int quadIndexCapacity, tlasCapacity;
	private static long tlasHandle;
	private static final Buf[] instanceBufs = new Buf[RING];
	private static long compactionQueries;
	@SuppressWarnings("unchecked")
	private static final List<Blas>[] builtInSlot = new List[RING];

	/** Per-frame BLAS of {@link Entities}; each slot is reused {@value #RING} frames later. */
	private static final Buf[] dynamicVertices = new Buf[RING];
	private static final Blas[] dynamicBlas = new Blas[RING];
	private static Buf dynamicScratch;
	private static boolean dynamicBuilt;

	/** For the trace passes; tlas is 0 when there's nothing to trace. Positions are relative to the anchor block. */
	public static long tlas, instanceInfo, lights;
	public static int anchorX, anchorY, anchorZ;
	/** Horizontal distance from the camera that the TLAS surely covers (whole sections within the RT distance). */
	public static int rtDistanceBlocks;
	/** Overlay stats. */
	public static int sectionCount, instanceCount, pendingCount, lastBuilds;
	/** A section within 2 sections of the camera was rebuilt this frame (a block changed nearby). */
	public static boolean nearbyRebuild;
	public static long tlasTriangles, dynamicTriangles;

	private Scene() {}

	public static void init(VkDevice device) {
		vk = device;
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(s).sType$Default();
			vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(asProps.address()));
			scratchAlignment = asProps.minAccelerationStructureScratchOffsetAlignment();
			LongBuffer h = s.mallocLong(1);
			check(vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(s).sType$Default()
				.queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(RING * MAX_BUILDS), null, h), "compaction query pool");
			compactionQueries = h.get(0);
			vkResetQueryPool(vk, compactionQueries, 0, RING * MAX_BUILDS);
		}
		for (int i = 0; i < RING; i++) builtInSlot[i] = new ArrayList<>();
		unshaded = HelioConfig.current.rayTracing;
		ready = true;
	}

	/** Compile worker thread: copy a freshly compiled section's layers. No layers means the section is empty now. */
	public static void submit(long node, Map<ChunkSectionLayer, MeshData> meshes, int[] lights) {
		if (!ready) return;
		Geo[] geos = null;
		for (Map.Entry<ChunkSectionLayer, MeshData> e : meshes.entrySet()) {
			MeshData mesh = e.getValue();
			if (!mesh.drawState().format().equals(DefaultVertexFormat.BLOCK)) {
				if (!warnedFormat) Helio.LOG.warn("Section mesh in unexpected vertex format {}; skipped", mesh.drawState().format());
				warnedFormat = true;
				continue;
			}
			int quads = mesh.drawState().vertexCount() / 4;
			if (quads == 0) continue;
			long bytes = (long) quads * 4 * VERTEX_STRIDE;
			Buf vertices = Memory.buffer(bytes, BUILD_INPUT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
			memCopy(memAddress(mesh.vertexBuffer()), vertices.mapped(), bytes);
			if (geos == null) geos = new Geo[ChunkSectionLayer.values().length];
			Micromaps.Plan plan = Helio.omm && e.getKey() == ChunkSectionLayer.CUTOUT ? Micromaps.plan(vertices.mapped(), quads) : null;
			int mask = LAYER_MASK[e.getKey().ordinal()];
			if (e.getKey() == ChunkSectionLayer.TRANSLUCENT) {
				// Vertex colour alpha is 255 - WATER_MARK on water (colour at byte 12, alpha its last byte).
				for (int q = 0; q < quads; q++) {
					if (memGetByte(vertices.mapped() + (long) q * 4 * VERTEX_STRIDE + 15) != (byte) (255 - WATER_MARK)) {
						mask |= MASK_NOT_ONLY_WATER;
						break;
					}
				}
			}
			geos[e.getKey().ordinal()] = new Geo(e.getKey().ordinal(), mask, vertices, quads, new Blas(), plan);
		}
		incoming.add(new Upload(node, geos, lights));
	}

	/** Vanilla reset a section (unloaded, moved out of range, or the renderer was rebuilt). Any thread. */
	public static void remove(long node) {
		if (ready) incoming.add(new Upload(node, null, null));
	}

	/** True on a compile worker while it runs SectionCompiler.compile. */
	public static final ThreadLocal<Boolean> COMPILING = ThreadLocal.withInitial(() -> Boolean.FALSE);
	/** True on a compile worker while it meshes a foliage block for the path tracer (SectionCompilerMixin). */
	public static final ThreadLocal<Boolean> TESSELATING_FOLIAGE = ThreadLocal.withInitial(() -> Boolean.FALSE);
	/** Sections are being compiled for the path tracer: no AO or face shading baked into vertex colours. */
	public static volatile boolean unshaded;

	/** Emission level of the fluid being tesselated (FluidRenderer has no other way to tell its vertices). */
	public static final ThreadLocal<Integer> FLUID_EMISSION = ThreadLocal.withInitial(() -> 0);
	/**
	 * Water's vertices claim emission 1, which no translucent block has: the path tracer reads it as water.
	 * Foliage does the same in the cutout layer (whose only real emission-1 block, the brewing stand, loses it),
	 * and plain glass claims 2, so the tracer refracts through its clear parts. Ice claims 2 in the translucent
	 * layer: shadows pass it untinted. Polished metal and gem blocks get levels past 15, which no block emits.
	 */
	public static final int WATER_MARK = 1, FOLIAGE_MARK = 1, GLASS_MARK = 2, ICE_MARK = 2, METAL_MARK = 32, GEM_MARK = 33;

	/** Polished blocks, by registry name: copper until it weathers. */
	private static final class Polished {
		static final Set<Block> METAL = new ReferenceOpenHashSet<>(), GEM = new ReferenceOpenHashSet<>();
		static {
			Set<String> gems = Set.of("diamond_block", "emerald_block", "lapis_block", "amethyst_block", "budding_amethyst", "redstone_block");
			for (Block b : BuiltInRegistries.BLOCK) {
				String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
				if (p.equals("iron_block") || p.equals("gold_block") || p.equals("netherite_block")
					|| p.contains("copper") && !p.contains("weathered") && !p.contains("oxidized") && !p.contains("ore") && !p.startsWith("raw")) METAL.add(b);
				if (gems.contains(p)) GEM.add(b);
			}
			Helio.LOG.info("Polished blocks: {} metal, {} gem", METAL.size(), GEM.size());
		}
	}

	/** What BlockModelLighterMixin writes into a block's vertex alpha: a material mark, else its emission level. */
	public static int mark(BlockState state) {
		if (isFoliage(state)) return FOLIAGE_MARK;
		if (isGlass(state)) return GLASS_MARK;
		if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) return ICE_MARK;
		int emission = state.getLightEmission();
		if (emission > 0) return emission;
		return Polished.METAL.contains(state.getBlock()) ? METAL_MARK : Polished.GEM.contains(state.getBlock()) ? GEM_MARK : 0;
	}

	/** Plain glass: its clear parts are cut out, so it's marked (stained glass refracts by its layer alone). */
	public static boolean isGlass(BlockState state) {
		return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE);
	}

	/** Leaves and plants: thin, so the path tracer lets light through them. */
	public static boolean isFoliage(BlockState state) {
		return state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE_BY_TREES) || state.is(BlockTags.FLOWERS)
			|| state.is(BlockTags.SAPLINGS) || state.is(BlockTags.CROPS);
	}

	public static boolean compilingUnshaded() {
		return unshaded && COMPILING.get();
	}

	/**
	 * Vertex alpha 255 - emission: shaders read the emission level back, and vanilla's 0.5 alpha cutoff and
	 * translucency barely notice (alpha stays above 0.94).
	 */
	public static int withEmission(int argb, int emission) {
		return emission == 0 ? argb : (argb & 0xFFFFFF) | (255 - emission) << 24;
	}

	/**
	 * Client tick: when the path tracer turns on or off, recompile every section so vanilla's own terrain
	 * (drawn when it's off, and translucent blocks always) gets its baked shading back, or loses it.
	 */
	public static void tick(Minecraft mc) {
		boolean want = Helio.rayTracing && HelioConfig.current.rayTracing;
		if (want == unshaded) return;
		unshaded = want;
		if (mc.level != null) mc.levelExtractor.allChanged();
	}

	/**
	 * Vanilla only compiles sections that are in view. Also offer it every section within the RT
	 * distance; its own dirty check then compiles those that need it, once.
	 */
	private static final ObjectArrayList<RenderSection> toCompile = new ObjectArrayList<>();

	public static ObjectArrayList<RenderSection> withHiddenSections(ViewArea area, ObjectArrayList<RenderSection> visible) {
		if (!ready || area == null) return visible;
		toCompile.clear();
		toCompile.addAll(visible);
		SectionPos c = area.getCameraSectionPos();
		int r = rtDistance();
		// ponytail: scans the whole view area each frame (~0.1 ms at 12 chunks); keep a dirty set if it shows up in profiles.
		for (RenderSection section : ((ViewAreaAccessor) area).helio$sections()) {
			long n = section.getSectionNode();
			if (Math.abs(SectionPos.x(n) - c.x()) <= r && Math.abs(SectionPos.z(n) - c.z()) <= r) toCompile.add(section);
		}
		return toCompile;
	}

	/** Inside the RT distance the path tracer draws water, glass and ice itself, so vanilla skips them. */
	public static boolean tracesTranslucent(long node, Vec3 camera) {
		if (!PathTracer.active()) return false;
		int r = rtDistance();
		return Math.abs(SectionPos.x(node) - SectionPos.blockToSectionCoord(camera.x)) <= r
			&& Math.abs(SectionPos.z(node) - SectionPos.blockToSectionCoord(camera.z)) <= r;
	}

	private static int rtDistance() {
		return Math.min(HelioConfig.current.rayTracingDistance, Minecraft.getInstance().options.getEffectiveRenderDistance());
	}

	/**
	 * Render thread, inside the frame's command buffer, before anything traces. With {@code build}
	 * false (ray tracing toggled off) only the queue is drained: no GPU work.
	 */
	public static void update(VkCommandBuffer cmd, Vec3 cameraPos, boolean build) {
		SectionPos camera = SectionPos.of(cameraPos);
		frame++;
		while (!deferred.isEmpty() && deferred.peek().frame + RING <= frame) deferred.poll().free.run();
		drainIncoming();
		if (frame % 64 == 0) evictOutOfRange(camera);
		pendingCount = pending.size();
		sectionCount = sections.size();
		tlas = 0;
		if (!build) return;

		try (MemoryStack s = MemoryStack.stackPush()) {
			GpuTimers.begin(cmd, GpuTimers.Pass.SCENE_BUILD);
			VulkanCommandEncoder.memoryBarrier(cmd, s); // earlier frames' traces and builds are done with what we overwrite
			int slot = (int) (frame % RING);
			compact(cmd, slot, s);
			buildPending(cmd, slot, camera, s);
			buildDynamic(cmd, slot, s);
			buildTlas(cmd, slot, camera, cameraPos, s);
			lights = Lights.upload(slot, cameraPos, rtDistance(), anchorX, anchorY, anchorZ);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			GpuTimers.end(cmd, GpuTimers.Pass.SCENE_BUILD);
		}
	}

	private static void drainIncoming() {
		for (Upload u; (u = incoming.poll()) != null; ) {
			Lights.put(u.node, u.lights);
			if (u.layers == null) {
				Geo[] stale = pending.remove(u.node);
				if (stale != null) freeGeos(stale);
				Geo[] live = sections.remove(u.node);
				if (live != null) deferFree(live);
			} else {
				Geo[] stale = pending.put(u.node, u.layers);
				if (stale != null) freeGeos(stale); // never reached the GPU
			}
		}
	}

	/** Drops sections a removal raced past (compiled after vanilla reset them): anything beyond the view area. */
	private static void evictOutOfRange(SectionPos camera) {
		int r = Minecraft.getInstance().options.getEffectiveRenderDistance() + 2;
		var it = sections.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			Long2ObjectMap.Entry<Geo[]> e = it.next();
			long n = e.getLongKey();
			if (Math.abs(SectionPos.x(n) - camera.x()) > r || Math.abs(SectionPos.z(n) - camera.z()) > r) {
				deferFree(e.getValue());
				Lights.put(n, null);
				it.remove();
			}
		}
	}

	/** Copies the BLASes built {@value #RING} frames ago into right-sized storage (typically half the size). */
	private static void compact(VkCommandBuffer cmd, int slot, MemoryStack s) {
		List<Blas> built = builtInSlot[slot];
		if (built.isEmpty()) return;
		LongBuffer sizes = s.mallocLong(built.size());
		int result = vkGetQueryPoolResults(vk, compactionQueries, slot * MAX_BUILDS, built.size(), sizes, 8, VK_QUERY_RESULT_64_BIT);
		vkResetQueryPool(vk, compactionQueries, slot * MAX_BUILDS, MAX_BUILDS);
		if (result == VK_SUCCESS) {
			VkCopyAccelerationStructureInfoKHR copy = VkCopyAccelerationStructureInfoKHR.calloc(s).sType$Default()
				.mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR);
			for (int i = 0; i < built.size(); i++) {
				Blas b = built.get(i);
				long size = sizes.get(i);
				if (b.dead || size == 0 || size >= b.storage.size()) continue;
				Buf oldStorage = b.storage;
				long oldHandle = b.handle;
				b.storage = Memory.buffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
				b.handle = createAs(s, b.storage, size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
				b.address = asAddress(s, b.handle);
				vkCmdCopyAccelerationStructureKHR(cmd, copy.src(oldHandle).dst(b.handle));
				defer(() -> {
					vkDestroyAccelerationStructureKHR(vk, oldHandle, null);
					oldStorage.free();
				});
			}
			asBarrier(cmd, s); // this frame's TLAS build reads the copies
		}
		built.clear();
	}

	private static void buildPending(VkCommandBuffer cmd, int slot, SectionPos camera, MemoryStack s) {
		nearbyRebuild = false;
		List<Geo> batch = new ArrayList<>();
		int triangles = 0, maxQuads = 0;
		var it = pending.long2ObjectEntrySet().fastIterator();
		while (it.hasNext() && batch.size() < MAX_BUILDS - 2 && triangles < MAX_TRIANGLES) {
			Long2ObjectMap.Entry<Geo[]> e = it.next();
			for (Geo g : e.getValue()) {
				if (g == null) continue;
				batch.add(g);
				triangles += g.quads * 2;
				maxQuads = Math.max(maxQuads, g.quads);
			}
			long node = e.getLongKey();
			Geo[] old = sections.put(node, e.getValue());
			if (old != null) deferFree(old);
			if (Math.abs(SectionPos.x(node) - camera.x()) <= 2 && Math.abs(SectionPos.y(node) - camera.y()) <= 2
				&& Math.abs(SectionPos.z(node) - camera.z()) <= 2) nearbyRebuild = true;
			it.remove();
		}
		lastBuilds = batch.size();
		if (batch.isEmpty()) return;
		ensureQuadIndices(maxQuads);
		buildMicromaps(cmd, slot, batch, s);

		int n = batch.size();
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(n);
		VkAccelerationStructureGeometryKHR.Buffer geoms = VkAccelerationStructureGeometryKHR.calloc(n);
		VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(n);
		PointerBuffer pRanges = memAllocPointer(n);
		VkAccelerationStructureTrianglesOpacityMicromapEXT.Buffer ommAttach = VkAccelerationStructureTrianglesOpacityMicromapEXT.calloc(n);
		VkMicromapUsageEXT.Buffer ommUsage = VkMicromapUsageEXT.calloc(n * (Micromaps.MAX_LEVEL + 1));
		LongBuffer handles = memAllocLong(n);
		long[] scratchOffsets = new long[n];
		try {
			VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
			IntBuffer count = s.mallocInt(1);
			long scratchTotal = 0;
			for (int i = 0; i < n; i++) {
				Geo g = batch.get(i);
				VkAccelerationStructureGeometryKHR geom = geoms.get(i).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
					.flags(g.layer == ChunkSectionLayer.SOLID.ordinal() ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
				VkAccelerationStructureGeometryTrianglesDataKHR tri = geom.geometry().triangles().sType$Default()
					.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(VERTEX_STRIDE).maxVertex(g.quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32);
				tri.vertexData().deviceAddress(g.vertices.address());
				tri.indexData().deviceAddress(quadIndices.address());
				if (g.blas.omm != null) {
					// Triangles per subdivision level, through the triangle -> entry index.
					int base = i * (Micromaps.MAX_LEVEL + 1), used = 0;
					int[] perLevel = g.blas.omm.triangleLevels();
					for (int l = 0; l < perLevel.length; l++) {
						if (perLevel[l] > 0) ommUsage.get(base + used++).count(perLevel[l]).subdivisionLevel(l).format(VK_OPACITY_MICROMAP_FORMAT_4_STATE_EXT);
					}
					VkAccelerationStructureTrianglesOpacityMicromapEXT attach = ommAttach.get(i).sType$Default()
						.indexType(VK_INDEX_TYPE_UINT32).indexStride(4).baseTriangle(0)
						.pUsageCounts(ommUsage.slice(base, used)).usageCountsCount(used).micromap(g.blas.omm.handle());
					attach.indexBuffer().deviceAddress(g.blas.omm.index().address());
					tri.pNext(attach.address());
				}
				VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(i).sType$Default()
					.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
					.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
					.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geoms.slice(i, 1)).geometryCount(1); // pGeometries() leaves the count at 0
				count.put(0, g.quads * 2);
				vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, count, sizes);
				Blas b = g.blas;
				b.storage = Memory.buffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
				b.handle = createAs(s, b.storage, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
				b.address = asAddress(s, b.handle);
				info.dstAccelerationStructure(b.handle);
				handles.put(i, b.handle);
				scratchOffsets[i] = align(scratchTotal, scratchAlignment);
				scratchTotal = scratchOffsets[i] + sizes.buildScratchSize();
				ranges.get(i).primitiveCount(g.quads * 2);
				pRanges.put(i, ranges.address(i));
			}
			if (scratch == null || scratch.size() < scratchTotal) {
				if (scratch != null) {
					Buf old = scratch;
					defer(old::free);
				}
				scratch = Memory.buffer(Math.max(scratchTotal, 64L << 20), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
			}
			for (int i = 0; i < n; i++) infos.get(i).scratchData().deviceAddress(scratch.address() + scratchOffsets[i]);

			vkCmdBuildAccelerationStructuresKHR(cmd, infos, pRanges);
			asBarrier(cmd, s);
			vkCmdWriteAccelerationStructuresPropertiesKHR(cmd, handles, VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
				compactionQueries, slot * MAX_BUILDS);
			for (Geo g : batch) builtInSlot[slot].add(g.blas);
		} finally {
			infos.free();
			geoms.free();
			ranges.free();
			memFree(pRanges);
			memFree(handles);
			ommAttach.free();
			ommUsage.free();
		}
	}

	/** Rebuilt from scratch every frame: entities move, animate and change shape. */
	private static void buildDynamic(VkCommandBuffer cmd, int slot, MemoryStack s) {
		int quads = Entities.quads;
		dynamicBuilt = quads > 0;
		dynamicTriangles = quads * 2L;
		if (!dynamicBuilt) return;
		ensureQuadIndices(quads);
		long bytes = (long) quads * 4 * Entities.STRIDE;
		Buf vertices = dynamicVertices[slot];
		if (vertices == null || vertices.size() < bytes) {
			if (vertices != null) vertices.free(); // this slot's last use was RING frames ago
			vertices = dynamicVertices[slot] = Memory.buffer(Math.max(bytes * 3 / 2, 1 << 20), BUILD_INPUT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		memCopy(Entities.vertices, vertices.mapped(), bytes);

		VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, s).sType$Default()
			.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(0); // alpha-tested against each entity's texture
		VkAccelerationStructureGeometryTrianglesDataKHR tri = geom.geometry().triangles().sType$Default()
			.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT).vertexStride(Entities.STRIDE).maxVertex(quads * 4 - 1).indexType(VK_INDEX_TYPE_UINT32);
		tri.vertexData().deviceAddress(vertices.address());
		tri.indexData().deviceAddress(quadIndices.address());
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, s).sType$Default()
			.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
			.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
		VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
		vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), s.ints(quads * 2), sizes);

		Blas b = dynamicBlas[slot];
		if (b == null || b.storage.size() < sizes.accelerationStructureSize()) {
			if (b != null) {
				vkDestroyAccelerationStructureKHR(vk, b.handle, null);
				b.storage.free();
			}
			b = dynamicBlas[slot] = new Blas();
			long size = sizes.accelerationStructureSize() * 3 / 2;
			b.storage = Memory.buffer(size, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			b.handle = createAs(s, b.storage, size, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
			b.address = asAddress(s, b.handle);
		}
		if (dynamicScratch == null || dynamicScratch.size() < sizes.buildScratchSize()) {
			if (dynamicScratch != null) {
				Buf old = dynamicScratch;
				defer(old::free);
			}
			dynamicScratch = Memory.buffer(sizes.buildScratchSize() * 3 / 2, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
		}
		info.dstAccelerationStructure(b.handle).scratchData().deviceAddress(dynamicScratch.address());
		vkCmdBuildAccelerationStructuresKHR(cmd, info, s.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, s).primitiveCount(quads * 2)));
		asBarrier(cmd, s);
	}

	/** Micromaps for the batch's cutout layers. */
	private static void buildMicromaps(VkCommandBuffer cmd, int slot, List<Geo> batch, MemoryStack s) {
		if (!Helio.omm) return;
		List<Geo> cutout = batch.stream().filter(g -> g.ommPlan != null).toList();
		if (cutout.isEmpty()) return;
		Micromaps.Plan[] plans = new Micromaps.Plan[cutout.size()];
		for (int i = 0; i < plans.length; i++) plans[i] = cutout.get(i).ommPlan;
		try {
			Micromaps.Micromap[] built = Micromaps.build(vk, cmd, slot, plans, s);
			for (int i = 0; i < built.length; i++) cutout.get(i).blas.omm = built[i];
		} catch (Exception e) {
			Helio.LOG.error("Opacity micromaps failed; cutout geometry uses the plain alpha test", e);
			Helio.omm = false;
		}
	}

	private static void buildTlas(VkCommandBuffer cmd, int slot, SectionPos camera, Vec3 cameraPos, MemoryStack s) {
		anchorX = camera.minBlockX();
		anchorY = camera.minBlockY();
		anchorZ = camera.minBlockZ();
		int r = rtDistance();
		rtDistanceBlocks = r * 16;

		int count = 0;
		for (Geo[] geos : sections.values()) for (Geo g : geos) if (g != null) count++;
		if (dynamicBuilt) count++;
		Buf buf = instanceBufs[slot];
		long bytes = (long) count * (VkAccelerationStructureInstanceKHR.SIZEOF + INFO_SIZE);
		if (buf == null || buf.size() < bytes) {
			if (buf != null) buf.free(); // this slot's last use was RING frames ago
			buf = instanceBufs[slot] = Memory.buffer(Math.max(bytes * 3 / 2, 1 << 16), BUILD_INPUT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}

		// Instances first, then one vertex-buffer address per instance for the hit shaders.
		long infoBase = (long) count * VkAccelerationStructureInstanceKHR.SIZEOF;
		int n = 0;
		long triangles = 0;
		var it = sections.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			Long2ObjectMap.Entry<Geo[]> e = it.next();
			long node = e.getLongKey();
			int sx = SectionPos.x(node), sy = SectionPos.y(node), sz = SectionPos.z(node);
			if (Math.abs(sx - camera.x()) > r || Math.abs(sz - camera.z()) > r) continue;
			float tx = (sx << 4) - anchorX, ty = (sy << 4) - anchorY, tz = (sz << 4) - anchorZ;
			for (Geo g : e.getValue()) {
				if (g == null) continue;
				putInstance(buf, infoBase, n++, tx, ty, tz, g.mask, g.blas.address, g.vertices.address(), g.quads * 2, g.layer);
				triangles += g.quads * 2L;
			}
		}
		// Entity positions are relative to the camera.
		if (dynamicBuilt) putInstance(buf, infoBase, n++, (float) (cameraPos.x - anchorX), (float) (cameraPos.y - anchorY),
			(float) (cameraPos.z - anchorZ), MASK_DYNAMIC, dynamicBlas[slot].address, dynamicVertices[slot].address(), Entities.quads * 2, KIND_DYNAMIC);
		instanceCount = n;
		tlasTriangles = triangles;
		if (n == 0) return;

		VkAccelerationStructureGeometryKHR.Buffer geom = VkAccelerationStructureGeometryKHR.calloc(1, s).sType$Default()
			.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
		geom.geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(buf.address());
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, s).sType$Default()
			.type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
			.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(geom).geometryCount(1);
		if (n > tlasCapacity) {
			if (tlasHandle != 0) {
				long oldHandle = tlasHandle;
				Buf oldStorage = tlasStorage, oldScratch = tlasScratch;
				defer(() -> {
					vkDestroyAccelerationStructureKHR(vk, oldHandle, null);
					oldStorage.free();
					oldScratch.free();
				});
			}
			tlasCapacity = Math.max(1024, Integer.highestOneBit(n) * 2);
			VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
			vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), s.ints(tlasCapacity), sizes);
			tlasStorage = Memory.buffer(sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false);
			tlasHandle = createAs(s, tlasStorage, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
			tlasScratch = Memory.buffer(sizes.buildScratchSize(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
		}
		info.dstAccelerationStructure(tlasHandle).scratchData().deviceAddress(tlasScratch.address());
		vkCmdBuildAccelerationStructuresKHR(cmd, info, s.pointers(VkAccelerationStructureBuildRangeInfoKHR.calloc(1, s).primitiveCount(n)));
		tlas = tlasHandle;
		instanceInfo = buf.address() + infoBase;
	}

	/**
	 * Per-instance info for shaders, {@value #INFO_SIZE} bytes: vertex-buffer address, triangle count, kind
	 * (section layer ordinal, or {@link #KIND_DYNAMIC} for entities, whose vertices are bare float3 positions).
	 */
	private static void putInstance(Buf buf, long infoBase, int n, float tx, float ty, float tz, int mask, long blasAddress,
									long vertexAddress, int triangles, int kind) {
		long p = buf.mapped() + (long) n * VkAccelerationStructureInstanceKHR.SIZEOF;
		memPutFloat(p, 1); memPutFloat(p + 4, 0); memPutFloat(p + 8, 0); memPutFloat(p + 12, tx);
		memPutFloat(p + 16, 0); memPutFloat(p + 20, 1); memPutFloat(p + 24, 0); memPutFloat(p + 28, ty);
		memPutFloat(p + 32, 0); memPutFloat(p + 36, 0); memPutFloat(p + 40, 1); memPutFloat(p + 44, tz);
		memPutInt(p + 48, mask << 24); // custom index 0
		memPutInt(p + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24); // SBT offset 0
		memPutLong(p + 56, blasAddress);
		long info = buf.mapped() + infoBase + (long) n * INFO_SIZE;
		memPutLong(info, vertexAddress);
		memPutInt(info + 8, triangles);
		memPutInt(info + 12, kind);
	}

	private static void ensureQuadIndices(int quads) {
		if (quads <= quadIndexCapacity) return;
		if (quadIndices != null) {
			Buf old = quadIndices;
			defer(old::free);
		}
		quadIndexCapacity = Math.max(1 << 16, Integer.highestOneBit(quads) * 2);
		quadIndices = Memory.buffer(quadIndexCapacity * 24L, BUILD_INPUT, true);
		IntBuffer idx = memIntBuffer(quadIndices.mapped(), quadIndexCapacity * 6);
		// Vanilla's quad order: 0 1 2, 2 3 0. Hit shaders rely on it to find a triangle's vertices.
		for (int q = 0; q < quadIndexCapacity; q++) {
			int v = q * 4;
			idx.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
		}
	}

	private static void asBarrier(VkCommandBuffer cmd, MemoryStack s) {
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0,
			VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
				.dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR), null, null);
	}

	private static long createAs(MemoryStack s, Buf storage, long size, int type) {
		LongBuffer h = s.mallocLong(1);
		check(vkCreateAccelerationStructureKHR(vk, VkAccelerationStructureCreateInfoKHR.calloc(s).sType$Default()
			.buffer(storage.buffer()).offset(storage.offset()).size(size).type(type), null, h), "create AS");
		return h.get(0);
	}

	private static long asAddress(MemoryStack s, long handle) {
		return vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(s).sType$Default().accelerationStructure(handle));
	}

	private static long align(long v, long a) {
		return (v + a - 1) / a * a;
	}

	static void defer(Runnable free) {
		deferred.add(new Deferred(frame, free));
	}

	/** Geometry earlier frames may still trace against. */
	private static void deferFree(Geo[] geos) {
		for (Geo g : geos) if (g != null) g.blas.dead = true;
		defer(() -> freeGeos(geos));
	}

	private static void freeGeos(Geo[] geos) {
		for (Geo g : geos) {
			if (g == null) continue;
			g.vertices.free();
			if (g.blas.handle != 0) {
				vkDestroyAccelerationStructureKHR(vk, g.blas.handle, null);
				g.blas.storage.free();
			}
			if (g.blas.omm != null) g.blas.omm.free(vk);
		}
	}

	/** Device closing; the GPU is idle. */
	public static void destroy() {
		if (!ready) return;
		ready = false;
		while (!deferred.isEmpty()) deferred.poll().free.run();
		for (Upload u; (u = incoming.poll()) != null; ) if (u.layers != null) freeGeos(u.layers);
		pending.values().forEach(Scene::freeGeos);
		sections.values().forEach(Scene::freeGeos);
		pending.clear();
		sections.clear();
		if (tlasHandle != 0) {
			vkDestroyAccelerationStructureKHR(vk, tlasHandle, null);
			tlasStorage.free();
			tlasScratch.free();
		}
		for (Buf b : instanceBufs) if (b != null) b.free();
		for (Buf b : dynamicVertices) if (b != null) b.free();
		for (Blas b : dynamicBlas) if (b != null) {
			vkDestroyAccelerationStructureKHR(vk, b.handle, null);
			b.storage.free();
		}
		if (dynamicScratch != null) dynamicScratch.free();
		if (scratch != null) scratch.free();
		if (quadIndices != null) quadIndices.free();
		vkDestroyQueryPool(vk, compactionQueries, null);
		Micromaps.destroy();
		Lights.destroy();
	}
}
