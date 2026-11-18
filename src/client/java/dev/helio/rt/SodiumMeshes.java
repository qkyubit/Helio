package dev.helio.rt;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * With Sodium installed (M8 step 5), sections are meshed by Sodium instead of vanilla, in its own compressed vertex
 * format, so {@link Scene#submit} never runs. Sodium's compile workers call in here (dev.helio.sodium) with
 * every quad's vertices before Sodium encodes them; this writes a second copy in vanilla's BLOCK layout, the way
 * Helio's own hooks would have made it (no baked shading: Sodium keeps that apart, as the vertex's "ao"; Scene.mark
 * in alpha; foliage kept out of the translucent layer), and hands the section to {@link Scene#submitRaw} when its
 * meshing task ends. Sodium's own meshes are untouched: it still draws them where Helio doesn't trace.
 */
public final class SodiumMeshes {
	private static final int LAYERS = ChunkSectionLayer.values().length, SOLID = ChunkSectionLayer.SOLID.ordinal(),
		CUTOUT = ChunkSectionLayer.CUTOUT.ordinal(), TRANSLUCENT = ChunkSectionLayer.TRANSLUCENT.ordinal();

	private static final class Section {
		final long[] buffers = new long[LAYERS];
		final int[] capacity = new int[LAYERS], quads = new int[LAYERS], filled = new int[LAYERS];
		boolean active;
		BlockAndTintGetter level;
		int layer, mark;
		boolean foliage, glass;
	}

	private static final ThreadLocal<Section> CURRENT = ThreadLocal.withInitial(Section::new);

	public static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

	private SodiumMeshes() {}

	/** A meshing task starts on this thread. */
	public static void begin() {
		Section s = CURRENT.get();
		s.active = Scene.ready();
		java.util.Arrays.fill(s.quads, 0);
		java.util.Arrays.fill(s.filled, 0);
		s.level = null;
	}

	/** The level slice Sodium meshes from (for the section's lights). */
	public static void level(BlockAndTintGetter level) {
		CURRENT.get().level = level;
	}

	/** A block's model is next; layer: its quads' pass (set again per quad by {@link #layer}). */
	public static void block(BlockState state) {
		Section s = CURRENT.get();
		s.mark = Scene.mark(state);
		s.foliage = Scene.isFoliage(state);
		s.glass = Scene.isGlass(state);
	}

	/** A fluid is next, in this layer (ChunkSectionLayer ordinal). */
	public static void fluid(BlockState state, FluidState fluid, int layer) {
		Section s = CURRENT.get();
		s.mark = fluid.is(FluidTags.WATER) ? Scene.WATER_MARK : state.getLightEmission();
		s.foliage = false;
		s.glass = false;
		s.layer = layer;
	}

	/** The layer (ChunkSectionLayer ordinal) of the quads that follow. */
	public static void layer(int layer) {
		CURRENT.get().layer = layer;
	}

	/**
	 * One vertex of a quad, as Sodium has it before encoding: position relative to the section, colour (tint) in
	 * ABGR, atlas UV, packed light. Four make a quad, in vanilla's order.
	 */
	public static void vertex(float x, float y, float z, int abgr, float u, float v, int light) {
		Section s = CURRENT.get();
		if (!s.active) return;
		// Foliage stays alpha-tested and plain glass translucent, as SectionCompilerMixin keeps them for vanilla
		// (connected-texture glass tiles, Continuity, would land in cutout).
		int layer = s.layer == TRANSLUCENT && s.foliage ? CUTOUT : s.layer == CUTOUT && s.glass ? TRANSLUCENT : s.layer;
		if (s.filled[layer] == s.capacity[layer] * 4) grow(s, layer);
		long p = s.buffers[layer] + (long) s.filled[layer] * Scene.VERTEX_STRIDE;
		memPutFloat(p, x);
		memPutFloat(p + 4, y);
		memPutFloat(p + 8, z);
		memPutInt(p + 12, (abgr & 0xFFFFFF) | (255 - s.mark) << 24);
		memPutFloat(p + 16, u);
		memPutFloat(p + 20, v);
		memPutInt(p + 24, light);
		if (++s.filled[layer] % 4 == 0) s.quads[layer]++;
	}

	private static void grow(Section s, int layer) {
		int quads = Math.max(256, s.capacity[layer] * 2);
		s.buffers[layer] = nmemRealloc(s.buffers[layer], (long) quads * 4 * Scene.VERTEX_STRIDE);
		if (s.buffers[layer] == NULL) throw new OutOfMemoryError("Helio: Sodium mesh copy");
		s.capacity[layer] = quads;
	}

	/** The meshing task ended (cancelled: section null). Hands the section to the scene. */
	public static void end(SectionPos section) {
		Section s = CURRENT.get();
		if (!s.active) return;
		s.active = false;
		if (section == null) return;
		int[] lights = s.level != null ? Lights.collect(s.level, section) : null;
		Scene.submitRaw(section.asLong(), s.buffers, s.quads, lights);
	}

	/** ChunkSectionLayer ordinal for Sodium's pass index (DefaultTerrainRenderPasses: solid, cutout, translucent). */
	public static int layerOf(boolean solid, boolean translucent) {
		return solid ? SOLID : translucent ? TRANSLUCENT : CUTOUT;
	}
}
