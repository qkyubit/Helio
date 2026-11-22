package dev.helio.lod;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import static dev.helio.lod.Lod.TILE;

/**
 * A LOD tile's mesh (M8 step 10b): a quad wherever a voxel's face meets air or a different see-through voxel
 * (water against air, leaves against air; not leaves against leaves, which would fill a canopy with faces),
 * merged greedily into rectangles of the same colour and light. Faces on the tile's sides look at the neighbouring
 * tiles; unknown neighbours count as air, so the world's edge is closed. Worker thread.
 * <p>
 * A quad is 4 ints, tile-local in voxels: x | y << 6 | z << 12 | w << 18 | h << 24 (w, h along the face's two
 * axes, in the order of {@link #AXES}); direction (Direction ordinal) | sky light << 3 | block light << 7; colour
 * (sRGB 0xRRGGBB); for water 1 | its alpha << 8, else 0. Quads come grouped: the rest by direction, then water by direction
 * (LodRenderer draws water after the opaque depth is copied, so the composite treats it as water).
 */
final class LodMesher {
	/** Per direction (down, up, north, south, west, east): the axis it faces along (0 x, 1 y, 2 z), its sign, and its face's u, v axes. */
	static final int[][] AXES = {{1, -1, 0, 2}, {1, 1, 0, 2}, {2, -1, 0, 1}, {2, 1, 0, 1}, {0, -1, 2, 1}, {0, 1, 2, 1}};

	private LodMesher() {}

	/** neighbours: the 6 tiles beside it, by direction (null: unknown). */
	static int[] mesh(long[] v, long[][] neighbours, Mapping m, LodColors colors) {
		IntArrayList out = new IntArrayList();
		long[] mask = new long[TILE * TILE];
		int[] p = new int[3];
		for (int pass = 0; pass < 2; pass++) for (int dir = 0; dir < 6; dir++) {
			boolean waterPass = pass == 1;
			int axis = AXES[dir][0], sign = AXES[dir][1], ua = AXES[dir][2], va = AXES[dir][3];
			for (int d = 0; d < TILE; d++) {
				boolean any = false;
				for (int vv = 0; vv < TILE; vv++) for (int u = 0; u < TILE; u++) {
					p[axis] = d;
					p[ua] = u;
					p[va] = vv;
					long self = v[Lod.index(p[0], p[1], p[2])];
					int s = Lod.state(self);
					long key = 0;
					if (s != 0 && m.water(s) == waterPass) {
						p[axis] = d + sign;
						long other = p[axis] < 0 || p[axis] >= TILE
							? neighbour(neighbours[dir], p, axis) : v[Lod.index(p[0], p[1], p[2])];
						int o = Lod.state(other);
						if (o == 0 || o != s && !m.solid(o)) {
							// Light from the voxel the face looks into; colour of the face itself.
							// In coarse steps (open sky from 12 up; else quarters): single-level differences stopped
							// neighbouring faces merging, for no visible change from far away.
							int sky = other == 0 ? 15 : Lod.sky(other), blk = Lod.blockLight(other);
							int light = (sky >= 12 ? 15 : sky & 12) | (blk & 12) << 4;
							key = 1L << 63 | (long) light << 32 | (colors.color(s, Lod.biome(self), dir) & 0xFFFFFFFFL);
							any = true;
						}
					}
					mask[vv * TILE + u] = key;
				}
				if (!any) continue;
				// Greedy: grow each unvisited face along u, then v, while the keys match.
				for (int vv = 0; vv < TILE; vv++) for (int u = 0; u < TILE; ) {
					long key = mask[vv * TILE + u];
					if (key == 0) {
						u++;
						continue;
					}
					int w = 1;
					while (u + w < TILE && mask[vv * TILE + u + w] == key) w++;
					int h = 1;
					grow:
					while (vv + h < TILE) {
						for (int k = 0; k < w; k++) if (mask[(vv + h) * TILE + u + k] != key) break grow;
						h++;
					}
					for (int j = 0; j < h; j++) for (int k = 0; k < w; k++) mask[(vv + j) * TILE + u + k] = 0;
					p[axis] = d + (sign > 0 ? 1 : 0); // the face's plane
					p[ua] = u;
					p[va] = vv;
					out.add(p[0] | p[1] << 6 | p[2] << 12 | w << 18 | h << 24);
					int light = (int) (key >>> 32 & 255);
					out.add(dir | (light & 15) << 3 | (light >> 4 & 15) << 7);
					out.add((int) (key & 0xFFFFFF));
					out.add(waterPass ? 1 | (int) (key >>> 24 & 255) << 8 : 0); // water: its alpha too
					u += w;
				}
			}
		}
		return out.toIntArray();
	}

	/** The voxel in a neighbouring tile at p (one past this tile's edge along axis). */
	private static long neighbour(long[] n, int[] p, int axis) {
		if (n == null) return 0;
		int x = p[0], y = p[1], z = p[2];
		if (axis == 0) x &= TILE - 1;
		else if (axis == 1) y &= TILE - 1;
		else z &= TILE - 1;
		return n[Lod.index(x, y, z)];
	}
}
