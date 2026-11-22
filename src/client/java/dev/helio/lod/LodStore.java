package dev.helio.lod;

import com.mojang.blaze3d.platform.NativeImage;
import dev.helio.Helio;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.material.MapColor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.helio.lod.Lod.TILE;

/**
 * LOD tiles on disk and in memory. Only the LOD worker thread uses it. Tiles are kept in vanilla's region file
 * container (its sector allocation and deflate compression): a file per level, tile row (y) and 32x32 tiles
 * ("L1_y2_r.0.-1.mca"), each tile in a chunk slot, written palette-packed. A tile never stored is unknown (not
 * ingested yet), drawn as nothing.
 */
final class LodStore implements AutoCloseable {
	private static final int CACHE = 384, FILES = 64, VOXELS = TILE * TILE * TILE;
	private static final RegionStorageInfo INFO = new RegionStorageInfo("helio", Level.OVERWORLD, "lod");

	private final Path dir;
	/** Tiles in memory, least recently used first; dirty ones are written when they leave. */
	private final LinkedHashMap<Long, Tile> cache = new LinkedHashMap<>(CACHE, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Tile> e) {
			if (size() <= CACHE) return false;
			if (e.getValue().dirty) write(e.getKey(), e.getValue());
			return true;
		}
	};
	private final LinkedHashMap<String, RegionFile> files = new LinkedHashMap<>(FILES, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, RegionFile> e) {
			if (size() <= FILES) return false;
			try {
				e.getValue().close();
			} catch (IOException ex) {
				Helio.LOG.warn("Helio LOD: closing {}", e.getKey(), ex);
			}
			return true;
		}
	};
	/** Per level: tiles changed since they were last mipped into the level above. */
	private final LongOpenHashSet[] changed = new LongOpenHashSet[Lod.MAX_LEVEL + 1];
	int tilesWritten;
	/** For hiding what can't be seen as tiles are written (Lod.hide). */
	Mapping mapping;

	static final class Tile {
		final long[] v = new long[VOXELS];
		boolean dirty;
	}

	LodStore(Path dir) {
		this.dir = dir;
		for (int i = 0; i < changed.length; i++) changed[i] = new LongOpenHashSet();
	}

	static long key(int level, int tx, int ty, int tz) {
		return (long) level << 60 | (long) (ty & 0xFF) << 52 | (long) (tx & 0x3FFFFFF) << 26 | tz & 0x3FFFFFF;
	}

	static int level(long key) {
		return (int) (key >>> 60);
	}

	static int ty(long key) {
		return (byte) (key >>> 52);
	}

	static int tx(long key) {
		return (int) (key >>> 26 & 0x3FFFFFF) << 6 >> 6;
	}

	static int tz(long key) {
		return (int) (key & 0x3FFFFFF) << 6 >> 6;
	}

	/** A tile, loaded if stored; null if it's unknown and create is false (else a new, all-unknown one). */
	Tile tile(long key, boolean create) {
		Tile t = cache.get(key);
		if (t != null) return t;
		t = read(key);
		if (t == null && !create) return null;
		if (t == null) t = new Tile();
		cache.put(key, t);
		return t;
	}

	void applyPatch(Lod.Patch p, Mapping m) {
		if (p.voxels() == null) { // a barrier
			mipAll(m);
			flush();
			p.task().run();
			return;
		}
		long key = key(1, p.tx(), p.ty(), p.tz());
		Tile t = tile(key, true);
		int n = 8;
		boolean same = true;
		for (int y = 0; y < n && same; y++) for (int z = 0; z < n && same; z++)
			same = java.util.Arrays.equals(p.voxels(), (y * n + z) * n, (y * n + z) * n + n, t.v, Lod.index(p.ox(), p.oy() + y, p.oz() + z), Lod.index(p.ox(), p.oy() + y, p.oz() + z) + n);
		// Unchanged (most chunks the client drops, or gets again): nothing to write, mip or mesh again.
		if (same) return;
		for (int y = 0; y < n; y++) for (int z = 0; z < n; z++)
			System.arraycopy(p.voxels(), (y * n + z) * n, t.v, Lod.index(p.ox(), p.oy() + y, p.oz() + z), n);
		t.dirty = true;
		changed[1].add(key);
		meshDirty.add(key);
	}

	/** Mips every changed tile into its parent's octant, level by level up to MAX_LEVEL. */
	void mipAll(Mapping m) {
		long[] c = new long[8];
		for (int level = 1; level < Lod.MAX_LEVEL; level++) {
			if (changed[level].isEmpty()) continue;
			long[] keys = changed[level].toLongArray();
			changed[level].clear();
			for (long key : keys) {
				Tile child = tile(key, false);
				if (child == null) continue;
				int tx = tx(key), ty = ty(key), tz = tz(key);
				long parentKey = key(level + 1, tx >> 1, ty >> 1, tz >> 1);
				Tile parent = tile(parentKey, true);
				int ox = (tx & 1) * 16, oy = (ty & 1) * 16, oz = (tz & 1) * 16;
				boolean same = true;
				for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
					for (int k = 0; k < 8; k++) c[k] = child.v[Lod.index(x * 2 + (k & 1), y * 2 + (k >> 2), z * 2 + (k >> 1 & 1))];
					long v = Lod.pick(c, m);
					int i = Lod.index(ox + x, oy + y, oz + z);
					if (parent.v[i] != v) {
						parent.v[i] = v;
						same = false;
					}
				}
				if (same) continue; // its mip didn't change: nor will the levels above
				parent.dirty = true;
				changed[level + 1].add(parentKey);
				meshDirty.add(parentKey);
			}
		}
		changed[Lod.MAX_LEVEL].clear();
		// Their meshes, and their neighbours' (faces at the edges look across): to be made again.
		if (!meshDirty.isEmpty()) {
			LongOpenHashSet all = new LongOpenHashSet();
			for (long k : meshDirty) {
				int l = level(k), x = tx(k), y = ty(k), z = tz(k);
				all.add(k);
				all.add(key(l, x - 1, y, z));
				all.add(key(l, x + 1, y, z));
				all.add(key(l, x, y - 1, z));
				all.add(key(l, x, y + 1, z));
				all.add(key(l, x, y, z - 1));
				all.add(key(l, x, y, z + 1));
			}
			meshDirty.clear();
			for (long k : all) Lod.meshChanged(k);
		}
	}

	/** Tiles changed since the last mipAll: their meshes go stale. */
	private final LongOpenHashSet meshDirty = new LongOpenHashSet();

	void flush() {
		for (Map.Entry<Long, Tile> e : cache.entrySet()) if (e.getValue().dirty) write(e.getKey(), e.getValue());
		for (RegionFile f : files.values()) {
			try {
				f.flush();
			} catch (IOException e) {
				Helio.LOG.warn("Helio LOD: flushing", e);
			}
		}
	}

	private RegionFile file(int level, int ty, int rx, int rz) throws IOException {
		String name = "L" + level + "_y" + ty + "_r." + rx + "." + rz + ".mca";
		RegionFile f = files.get(name);
		if (f == null) {
			f = new RegionFile(INFO, dir.resolve(name), dir, RegionFileVersion.VERSION_DEFLATE, false);
			files.put(name, f);
		}
		return f;
	}

	/** A tile palette-packed: the distinct voxels, then each voxel's index in as few bits as they need. */
	private void write(long key, Tile t) {
		int tx = tx(key), tz = tz(key);
		long[] voxels = t.v;
		if (mapping != null) {
			voxels = t.v.clone(); // hidden as written, not in the cache: a fresh copy of the same chunk must still compare equal
			Lod.hide(voxels, mapping);
		}
		try (DataOutputStream out = file(level(key), ty(key), tx >> 5, tz >> 5).getChunkDataOutputStream(new ChunkPos(tx, tz))) {
			Long2IntOpenHashMap palette = new Long2IntOpenHashMap();
			palette.defaultReturnValue(-1);
			long[] values = new long[16];
			for (long v : voxels) {
				if (palette.get(v) >= 0) continue;
				if (palette.size() == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
				values[palette.size()] = v;
				palette.put(v, palette.size());
			}
			int n = palette.size();
			out.writeInt(n);
			for (int i = 0; i < n; i++) out.writeLong(values[i]);
			if (n > 1) {
				int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
				long word = 0;
				int used = 0;
				for (long v : voxels) {
					long idx = palette.get(v);
					word |= idx << used;
					used += bits;
					if (used >= 64) {
						out.writeLong(word);
						used -= 64;
						word = used > 0 ? idx >>> (bits - used) : 0;
					}
				}
				if (used > 0) out.writeLong(word);
			}
			t.dirty = false;
			tilesWritten++;
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: writing tile {} {} {} at level {}", tx, ty(key), tz, level(key), e);
		}
	}

	private Tile read(long key) {
		int tx = tx(key), tz = tz(key);
		Path path = dir.resolve("L" + level(key) + "_y" + ty(key) + "_r." + (tx >> 5) + "." + (tz >> 5) + ".mca");
		if (!files.containsKey(path.getFileName().toString()) && !Files.exists(path)) return null;
		try {
			RegionFile f = file(level(key), ty(key), tx >> 5, tz >> 5);
			try (DataInputStream in = f.getChunkDataInputStream(new ChunkPos(tx, tz))) {
				if (in == null) return null;
				int n = in.readInt();
				long[] values = new long[n];
				for (int i = 0; i < n; i++) values[i] = in.readLong();
				Tile t = new Tile();
				if (n == 1) java.util.Arrays.fill(t.v, values[0]);
				else {
					int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
					long mask = (1L << bits) - 1, word = in.readLong();
					int used = 0;
					for (int i = 0; i < VOXELS; i++) {
						long idx = word >>> used;
						used += bits;
						if (used >= 64) {
							used -= 64;
							word = i + 1 < VOXELS || used > 0 ? in.readLong() : 0;
							if (used > 0) idx |= word << (bits - used);
						}
						t.v[i] = values[(int) (idx & mask)];
					}
				}
				return t;
			}
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: reading tile {} {} {} at level {}", tx, ty(key), tz, level(key), e);
			return null;
		}
	}

	/**
	 * A top-down picture of a level around (cx, cz), 1024 voxels each way at most: each column's top voxel in its
	 * map colour, darker where it's lower than its north neighbour (as vanilla maps shade).
	 */
	void dumpMap(int level, Mapping m, int cx, int cz, Path out) {
		int size = Math.min(1024, 16384 >> level), half = size / 2, vs = 1 << level;
		int x0 = Math.floorDiv(cx, vs) - half, z0 = Math.floorDiv(cz, vs) - half;
		int[] top = new int[size * size], color = new int[size * size];
		java.util.Arrays.fill(top, Integer.MIN_VALUE);
		int tiles = 0;
		for (int tz = Math.floorDiv(z0, TILE); tz <= Math.floorDiv(z0 + size - 1, TILE); tz++)
			for (int tx = Math.floorDiv(x0, TILE); tx <= Math.floorDiv(x0 + size - 1, TILE); tx++)
				for (int ty = 7; ty >= -8; ty--) {
					Tile t = tile(key(level, tx, ty, tz), false);
					if (t == null) continue;
					tiles++;
					for (int z = 0; z < TILE; z++) for (int x = 0; x < TILE; x++) {
						int px = tx * TILE + x - x0, pz = tz * TILE + z - z0;
						if (px < 0 || pz < 0 || px >= size || pz >= size) continue;
						int i = pz * size + px;
						for (int y = TILE - 1; y >= 0; y--) {
							int s = Lod.state(t.v[Lod.index(x, y, z)]);
							if (s == 0) continue;
							int wy = ty * TILE + y;
							if (wy > top[i]) {
								top[i] = wy;
								BlockState st = m.stateOf(s);
								color[i] = st.getBlock().defaultMapColor().calculateARGBColor(MapColor.Brightness.NORMAL);
								if (!st.getFluidState().isEmpty()) color[i] = 0xFF3F76E4;
							}
							break;
						}
					}
				}
		try (NativeImage img = new NativeImage(size, size, false)) {
			for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
				int i = z * size + x, c = top[i] == Integer.MIN_VALUE ? 0xFF000000 : color[i];
				if (z > 0 && top[i] != Integer.MIN_VALUE && top[i - size] > top[i]) c = (c & 0xFF000000) | (c >> 1 & 0x7F7F7F) + (c >> 2 & 0x3F3F3F);
				img.setPixel(x, z, c);
			}
			img.writeToFile(out);
			Helio.LOG.info("Helio LOD: level {} map ({} tiles, {}x{}) written to {}", level, tiles, size, size, out);
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: writing {}", out, e);
		}
	}

	/** A tile's mesh (LodMesher), its neighbours looked up here. Null if the tile is unknown. */
	int[] mesh(long key, Mapping m, LodColors colors) {
		Tile t = tile(key, false);
		if (t == null) return null;
		int level = level(key), tx = tx(key), ty = ty(key), tz = tz(key);
		long[][] n = new long[6][];
		int[][] off = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
		for (int d = 0; d < 6; d++) {
			Tile o = tile(key(level, tx + off[d][0], ty + off[d][1], tz + off[d][2]), false);
			n[d] = o == null ? null : o.v;
		}
		t = tile(key, false); // may have left the cache meanwhile: fetched again
		return t == null ? null : LodMesher.mesh(t.v, n, m, colors);
	}

	/**
	 * Bench word "lodmesh:<level>": meshes the tiles within 8 of the player's at that level, logs their counts and
	 * times, and draws their upward faces from above (helio_lodmesh_<level>.png), to check colours and tints.
	 */
	void meshTest(int level, Mapping m, LodColors colors, int cx, int cz, Path out) {
		int R = 8, size = (2 * R + 1) * TILE, vs = 1 << level;
		int ctx = Math.floorDiv(Math.floorDiv(cx, vs), TILE), ctz = Math.floorDiv(Math.floorDiv(cz, vs), TILE);
		int[] top = new int[size * size], color = new int[size * size];
		java.util.Arrays.fill(top, Integer.MIN_VALUE);
		long quads = 0, faces = 0, nanos = 0;
		int tiles = 0;
		for (int tz = ctz - R; tz <= ctz + R; tz++) for (int tx = ctx - R; tx <= ctx + R; tx++) for (int ty = -8; ty <= 7; ty++) {
			long t0 = System.nanoTime();
			int[] q = mesh(key(level, tx, ty, tz), m, colors);
			nanos += System.nanoTime() - t0;
			if (q == null) continue;
			tiles++;
			quads += q.length / 4;
			for (int i = 0; i < q.length; i += 4) {
				int pos = q[i], w = pos >>> 18 & 63, h = pos >>> 24 & 63;
				faces += (long) w * h;
				if ((q[i + 1] & 7) != 1) continue; // up
				int x = (tx - ctx + R) * TILE + (pos & 63), y = ty * TILE + (pos >> 6 & 63), z = (tz - ctz + R) * TILE + (pos >> 12 & 63);
				for (int j = 0; j < h; j++) for (int k = 0; k < w; k++) {
					int idx = (z + j) * size + x + k;
					if (idx < 0 || idx >= top.length || y <= top[idx]) continue;
					top[idx] = y;
					color[idx] = 0xFF000000 | q[i + 2];
				}
			}
		}
		Helio.LOG.info("Helio LOD: level {} meshes: {} tiles, {} quads ({} faces before merging, {} a tile), {} ms ({} ms a tile)", level, tiles, quads, faces,
			tiles == 0 ? 0 : quads / tiles, nanos / 1_000_000, tiles == 0 ? 0 : nanos / 1_000_000.0 / tiles);
		try (NativeImage img = new NativeImage(size, size, false)) {
			for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
				int i = z * size + x, c = top[i] == Integer.MIN_VALUE ? 0xFF000000 : color[i];
				if (z > 0 && top[i] != Integer.MIN_VALUE && top[i - size] > top[i]) c = (c & 0xFF000000) | (c >> 1 & 0x7F7F7F) + (c >> 2 & 0x3F3F3F);
				img.setPixel(x, z, c);
			}
			img.writeToFile(out);
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: writing {}", out, e);
		}
	}

	@Override
	public void close() throws IOException {
		flush();
		for (RegionFile f : files.values()) f.close();
		files.clear();
		cache.clear();
	}
}
