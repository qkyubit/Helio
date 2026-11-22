package dev.helio.lod;

import dev.helio.Helio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Helio's own LODs (M8 step 10a: the data). Far terrain as voxels in tiles of 32³, level L a voxel per 2^L blocks
 * (levels 1 to {@link #MAX_LEVEL}; level 6 is 64-block voxels, about 5 pixels at 12,800 blocks). There's no level
 * 0: in singleplayer the save already holds the blocks, so a section (16³ blocks) goes straight into an 8³ patch
 * of a level-1 tile, and each level is mipped from the one below. Sources: chunks as the client receives and
 * drops them (edits included), and in singleplayer an import of the save (LodImport), so a pre-generated world
 * has its LODs at once. Tiles live in LodStore, which only the worker thread touches.
 * <p>
 * A voxel is a long: Helio's id of its block state (Mapping; 0 = air), its biome, its sky and block light, and
 * KNOWN (0 is a voxel nothing's known of).
 */
public final class Lod {
	public static final int TILE = 32, MAX_LEVEL = 6;
	/** Tiles whose voxels changed, per level, waiting to be mipped into the level above. */
	private static final int PATCH = 8; // a section's voxels at level 1, each way

	private Lod() {}

	/** Set on every voxel ingested: 0 is unknown (never seen, or above the terrain: all-air sections are skipped). */
	public static final long KNOWN = 1L << 40;

	public static long voxel(int state, int biome, int sky, int block) {
		return state & 0xFFFFFL | (long) (biome & 0xFFF) << 20 | (long) (sky & 15) << 32 | (long) (block & 15) << 36 | KNOWN;
	}

	public static int state(long v) {
		return (int) (v & 0xFFFFF);
	}

	public static int biome(long v) {
		return (int) (v >>> 20 & 0xFFF);
	}

	public static int sky(long v) {
		return (int) (v >>> 32 & 15);
	}

	public static int blockLight(long v) {
		return (int) (v >>> 36 & 15);
	}

	/** Index of a voxel in a tile: x fastest, then z, then y. */
	public static int index(int x, int y, int z) {
		return (y * TILE + z) * TILE + x;
	}

	/**
	 * One voxel for 8 below it (c: x, then z, then y, each 0..1). The top layer wins if anything's in it, else
	 * the bottom (a canopy over a trunk, a lake over its bed). Plants (replaceable, no fluid, not snow) give way
	 * to solid blocks under them, so meadows show their grass blocks. Light: the most of any of the 8, air
	 * included: faces take it from the air beside them.
	 */
	static long pick(long[] c, Mapping m) {
		int sky = 0, block = 0;
		boolean known = false;
		for (long v : c) {
			known |= v != 0;
			sky = Math.max(sky, sky(v));
			block = Math.max(block, blockLight(v));
		}
		long best = 0;
		int bestScore = 0;
		for (int layer = 1; layer >= 0 && bestScore < 3; layer--) {
			for (int i = layer * 4; i < layer * 4 + 4; i++) {
				int s = state(c[i]);
				if (s == 0) continue;
				int score = m.plant(s) ? 1 : m.solid(s) ? 3 : 2;
				if (score > bestScore) {
					bestScore = score;
					best = c[i];
				}
			}
			if (bestScore >= 2) break; // something more than a plant in this layer
		}
		if (!known) return 0;
		return bestScore == 0 ? voxel(0, 0, sky, block) : voxel(state(best), biome(best), sky, block);
	}

	// --- The session: one level (dimension) of one world at a time ---

	private static ClientLevel level;
	static LodStore store;
	static Mapping mapping;
	private static LodImport importer;
	/** The worker: all tile edits, mips and writes, in order. */
	static ExecutorService worker;
	/** Level-1 patches from sections, for the worker: bounded, so an import waits rather than piling up. */
	private static final BlockingQueue<Patch> patches = new ArrayBlockingQueue<>(4096);

	/** voxels null: a barrier, task run once everything before it is applied, mipped and written. */
	record Patch(int tx, int ty, int tz, int ox, int oy, int oz, long[] voxels, Runnable task) {}

	/** Chunks the client got, by the tick to ingest them at: their light comes in after them. */
	private static final java.util.ArrayDeque<Object[]> arrived = new java.util.ArrayDeque<>();
	private static long ticks;
	private static final int LIGHT_WAIT = 40;

	/** Client tick: opens the LOD store of the level the player is in, closing the last one's. */
	public static void tick(Minecraft mc) {
		ticks++;
		while (!arrived.isEmpty() && (long) arrived.peekFirst()[1] <= ticks) {
			LevelChunk c = (LevelChunk) arrived.pollFirst()[0];
			if (mc.level != null && mc.level == level && mc.level.getChunkSource().getChunkNow(c.getPos().x(), c.getPos().z()) == c) ingest(mc.level, c);
		}
		if (mc.level == level) return;
		arrived.clear();
		close();
		level = mc.level;
		if (level == null) return;
		Path dir;
		var server = mc.getSingleplayerServer();
		var dim = level.dimension().identifier();
		if (server != null) dir = server.getWorldPath(LevelResource.ROOT).resolve("helio_lod");
		else {
			ServerData data = mc.getCurrentServer();
			String name = data != null ? data.ip.replaceAll("[^A-Za-z0-9._-]", "_") : "unknown";
			dir = mc.gameDirectory.toPath().resolve("helio_lod").resolve(name);
		}
		dir = dir.resolve(dim.getNamespace()).resolve(dim.getPath());
		try {
			java.nio.file.Files.createDirectories(dir);
			mapping = new Mapping(dir, level.registryAccess());
			store = new LodStore(dir);
			store.mapping = mapping;
		} catch (Exception e) {
			Helio.LOG.error("Helio: couldn't open the LOD store in {}", dir, e);
			store = null;
			return;
		}
		worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "Helio LOD");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		worker.execute(Lod::drainLoop);
		Helio.LOG.info("Helio LODs: {}", dir);
		if (server != null) importer = LodImport.start(server, level.dimension(), dir);
	}

	private static void close() {
		if (importer != null) importer.stop();
		importer = null;
		if (worker != null) {
			running = false;
			ExecutorService w = worker;
			LodStore s = store;
			w.execute(() -> {
				try {
					if (s != null) s.close();
				} catch (Exception e) {
					Helio.LOG.warn("Helio: closing the LOD store", e);
				}
			});
			w.shutdown();
			try {
				w.awaitTermination(30, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
		patches.clear();
		worker = null;
		store = null;
		mapping = null;
	}

	private static volatile boolean running;

	/** The worker's loop: applies patches as they come; mips and writes when there's a lull or every so often. */
	private static void drainLoop() {
		running = true;
		long lastMip = System.nanoTime();
		while (running) {
			try {
				Patch p = patches.poll(50, TimeUnit.MILLISECONDS);
				if (p != null) store.applyPatch(p, mapping);
				long now = System.nanoTime();
				if (p == null || now - lastMip > 2_000_000_000L) {
					store.mipAll(mapping);
					lastMip = now;
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				Helio.LOG.error("Helio LOD worker", t);
			}
		}
	}

	/** Runs a task on the worker once everything queued before it is applied, mipped and written. */
	static void afterPatches(Runnable task) {
		if (worker == null) return;
		try {
			patches.put(new Patch(0, 0, 0, 0, 0, 0, null, task));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	static void submit(Patch p) throws InterruptedException {
		patches.put(p);
	}

	// --- Client chunks ---

	/** A chunk the client got: ingested LIGHT_WAIT ticks on, once its light has come (it was all dark at first). */
	public static void onChunkLoad(ClientLevel l, LevelChunk chunk) {
		if (l == level && store != null) arrived.addLast(new Object[] {chunk, ticks + LIGHT_WAIT});
	}

	/** A chunk the client dropped: ingested as it was left (edits since it came). */
	public static void onChunkUnload(ClientLevel l, LevelChunk chunk) {
		ingest(l, chunk);
	}

	/** A client chunk (render thread): its sections, copied now, become patches off it. */
	private static void ingest(ClientLevel l, LevelChunk chunk) {
		if (l != level || store == null) return;
		LevelChunkSection[] sections = chunk.getSections();
		int minY = l.getMinSectionY(), cx = chunk.getPos().x(), cz = chunk.getPos().z();
		var sky = l.getLightEngine().getLayerListener(LightLayer.SKY);
		var blk = l.getLightEngine().getLayerListener(LightLayer.BLOCK);
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null || s.hasOnlyAir()) continue;
			SectionPos pos = SectionPos.of(cx, minY + i, cz);
			PalettedContainer<BlockState> states = s.getStates().copy();
			Holder<Biome>[] biomes = biomes(s.getBiomes());
			DataLayer skyData = copy(sky.getDataLayerData(pos)), blockData = copy(blk.getDataLayerData(pos));
			LodImport.POOL.execute(() -> {
				try {
					submit(patch(pos, states, biomes, skyData, blockData, mapping));
				} catch (InterruptedException ignored) {
				} catch (Throwable t) {
					Helio.LOG.warn("Helio LOD: section {}", pos, t);
				}
			});
		}
	}

	private static DataLayer copy(DataLayer d) {
		return d == null ? null : d.copy();
	}

	@SuppressWarnings("unchecked")
	static Holder<Biome>[] biomes(PalettedContainerRO<Holder<Biome>> b) {
		Holder<Biome>[] out = new Holder[64];
		for (int i = 0; i < 64; i++) out[i] = b.get(i & 3, i >> 4, i >> 2 & 3);
		return out;
	}

	/**
	 * A section as an 8³ patch of its level-1 tile: each voxel picked from its 2³ blocks. sky null: no light data
	 * there, taken as open sky (sections above the terrain).
	 */
	static Patch patch(SectionPos pos, PalettedContainer<BlockState> states, Holder<Biome>[] biomes, DataLayer sky, DataLayer block, Mapping m) {
		long[] out = new long[PATCH * PATCH * PATCH], c = new long[8];
		int[] biomeIds = new int[64];
		for (int i = 0; i < 64; i++) biomeIds[i] = m.biome(biomes[i]);
		java.util.IdentityHashMap<BlockState, Integer> ids = new java.util.IdentityHashMap<>();
		for (int y = 0; y < PATCH; y++) for (int z = 0; z < PATCH; z++) for (int x = 0; x < PATCH; x++) {
			for (int k = 0; k < 8; k++) {
				int bx = x * 2 + (k & 1), bz = z * 2 + (k >> 1 & 1), by = y * 2 + (k >> 2);
				BlockState st = states.get(bx, by, bz);
				int id = ids.computeIfAbsent(st, m::state);
				c[k] = voxel(id, biomeIds[(by >> 2) * 16 + (bz >> 2) * 4 + (bx >> 2)],
					sky == null ? 15 : sky.get(bx, by, bz), block == null ? 0 : block.get(bx, by, bz));
			}
			out[(y * PATCH + z) * PATCH + x] = pick(c, m);
		}
		// Its place in the level-1 tile: a tile is 64 blocks, 4 sections, each way.
		return new Patch(pos.x() >> 2, pos.y() >> 2, pos.z() >> 2, (pos.x() & 3) * PATCH, (pos.y() & 3) * PATCH, (pos.z() & 3) * PATCH, out, null);
	}

	/**
	 * What can't be seen from outside becomes one value: solid voxels with nothing open beside them, and air the
	 * sky never reaches (caves), as stone. Stored tiles shrink to their surfaces (most of a tile is underground),
	 * and nothing visible changes. Within the tile: voxels on its faces stay as they are, since the neighbouring
	 * tile isn't known here. Open: air with sky light, anything not solid (water, leaves), or unknown (all-air
	 * sections above the terrain aren't ingested: taken as closed, the sky turned to stone). LodStore, as a tile
	 * is written.
	 */
	static void hide(long[] v, Mapping m) {
		long hidden = voxel(m.state(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()), 0, 0, 0);
		boolean[] open = new boolean[v.length];
		for (int i = 0; i < v.length; i++) {
			int s = state(v[i]);
			open[i] = v[i] == 0 || (s == 0 ? sky(v[i]) > 0 : !m.solid(s)); // unknown counts as open
		}
		int row = TILE, layer = TILE * TILE;
		for (int y = 1; y < TILE - 1; y++) for (int z = 1; z < TILE - 1; z++) for (int x = 1; x < TILE - 1; x++) {
			int i = index(x, y, z);
			if (open[i] || open[i - 1] || open[i + 1] || open[i - row] || open[i + row] || open[i - layer] || open[i + layer]) continue;
			v[i] = hidden;
		}
	}

	/** Bench word "lodmap:<level>": a top-down picture of that level's tiles around the player, by the worker. */
	public static void dumpMap(int lod) {
		var mc = Minecraft.getInstance();
		if (store == null || mc.player == null) return;
		Path out = mc.gameDirectory.toPath().resolve("helio_lodmap_" + lod + ".png");
		int x = mc.player.getBlockX(), z = mc.player.getBlockZ();
		LodStore s = store;
		Mapping m = mapping;
		afterPatches(() -> s.dumpMap(lod, m, x, z, out));
	}
}
