package dev.helio.rt;

import com.mojang.blaze3d.platform.NativeImage;
import dev.helio.mixin.SpriteContentsAccessor;
import dev.helio.vk.Memory;
import dev.helio.vk.Memory.Buf;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Light-emitting blocks (torches, lava, glowstone...). Collected per section on the compile worker; each
 * frame the path tracer gets a table of the sections around the camera (each block light reaches at most 15
 * blocks, so a point is only lit from its own section and the 26 around it) and samples from it.
 * <p>
 * GPU layout, per frame: header {ivec4 origin (table's min section, relative to the anchor section; w = light
 * count), ivec4 dims, uint64 address of the light array, 8 bytes padding}, then one uvec2 {first light, count} per section
 * (x fastest, then y, then z), then lights {vec4 block centre relative to the anchor + emission level,
 * vec4 linear colour}.
 */
public final class Lights {
	private static final int HEADER = 48, LIGHT_SIZE = 32, RING = 4;

	/** Light colour per block state: average of its particle texture's brightest opaque texels, linear. */
	private static final Map<BlockState, float[]> colours = new ConcurrentHashMap<>();
	/** Two ints per light: x | y << 4 | z << 8 | emission << 12, then its colour (packed RGB, sRGB). */
	private static final Long2ObjectOpenHashMap<int[]> bySection = new Long2ObjectOpenHashMap<>();
	private static final Buf[] buffers = new Buf[RING];
	public static int count;

	private Lights() {}

	/** Compile worker: the section's emitters that can light anything, or null if none. */
	public static int[] collect(BlockAndTintGetter region, SectionPos section) {
		int[] out = null;
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), next = new BlockPos.MutableBlockPos();
		for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
			BlockState state = region.getBlockState(pos.set(section.minBlockX() + x, section.minBlockY() + y, section.minBlockZ() + z));
			int emission = state.getLightEmission();
			if (emission == 0 || !exposed(region, pos, next)) continue;
			if (out == null) out = new int[16];
			else if (n + 2 > out.length) out = Arrays.copyOf(out, out.length * 2);
			out[n++] = x | y << 4 | z << 8 | emission << 12;
			out[n++] = colourOf(state);
		}
		return out == null ? null : Arrays.copyOf(out, n);
	}

	/**
	 * Lava buried in rock or under more lava can't light anything, yet outnumbers useful lights by
	 * thousands and would swamp the sampling: an emitter counts only if a neighbour lets light out.
	 */
	private static boolean exposed(BlockAndTintGetter region, BlockPos pos, BlockPos.MutableBlockPos next) {
		for (Direction d : Direction.values()) {
			BlockState neighbour = region.getBlockState(next.setWithOffset(pos, d));
			if (!neighbour.isSolidRender() && !neighbour.getFluidState().is(FluidTags.LAVA)) return true;
		}
		return false;
	}

	private static int colourOf(BlockState state) {
		float[] c = colours.computeIfAbsent(state, Lights::averageBrightTexels);
		return (int) (c[0] * 255) << 16 | (int) (c[1] * 255) << 8 | (int) (c[2] * 255);
	}

	/** Mean of the brightest quarter of opaque texels: the flame of a torch, not its stick. */
	private static float[] averageBrightTexels(BlockState state) {
		try {
			NativeImage image = ((SpriteContentsAccessor) Minecraft.getInstance().getModelManager().getBlockStateModelSet()
				.get(state).particleMaterial().sprite().contents()).helio$originalImage();
			int w = image.getWidth(), h = Math.min(image.getHeight(), (1 << 20) / image.getWidth());
			int[] lum = new int[w * h];
			int opaque = 0;
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
				int argb = image.getPixel(x, y);
				if ((argb >>> 24) < 128) continue;
				// Sort key: luminance-ish (11 bits) above the texel index (20 bits; animated strips fit).
				lum[opaque++] = ((argb >> 16 & 255) * 2 + (argb >> 8 & 255) * 5 + (argb & 255)) << 20 | (y * w + x);
			}
			if (opaque == 0) return new float[]{1, 0.7f, 0.4f};
			int[] sorted = Arrays.copyOf(lum, opaque);
			Arrays.sort(sorted);
			float r = 0, g = 0, b = 0;
			int take = Math.max(1, opaque / 4);
			for (int i = opaque - take; i < opaque; i++) {
				int idx = sorted[i] & 0xFFFFF;
				int argb = image.getPixel(idx % w, idx / w);
				r += (argb >> 16 & 255);
				g += (argb >> 8 & 255);
				b += (argb & 255);
			}
			float max = Math.max(r, Math.max(g, b));
			return max == 0 ? new float[]{1, 1, 1} : new float[]{r / max, g / max, b / max};
		} catch (RuntimeException e) {
			return new float[]{1, 0.7f, 0.4f};
		}
	}

	/** Render thread, with the scene's section updates. */
	static void put(long node, int[] lights) {
		if (lights == null) bySection.remove(node);
		else bySection.put(node, lights);
	}

	/** Uploads this frame's table; returns its device address (0: no lights). */
	static long upload(int slot, SectionPos camera, int r, int anchorX, int anchorY, int anchorZ) {
		var level = Minecraft.getInstance().level;
		if (level == null) return 0;
		int minY = level.getMinSectionY(), height = level.getSectionsCount();
		int w = 2 * r + 1, cells = w * height * w;
		int total = 0;
		for (int[] l : bySection.values()) total += l.length / 2;
		long tableBytes = (long) cells * 8, lightsOffset = HEADER + (tableBytes + 15) / 16 * 16;
		long bytes = lightsOffset + (long) total * LIGHT_SIZE;
		Buf buf = buffers[slot];
		if (buf == null || buf.size() < bytes) {
			if (buf != null) buf.free(); // this slot's last use was RING frames ago
			buf = buffers[slot] = Memory.buffer(Math.max(bytes * 3 / 2, 1 << 20), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		}
		long p = buf.mapped();
		memSet(p + HEADER, 0, tableBytes);
		int ox = camera.x() - r, oz = camera.z() - r, n = 0;
		for (Long2ObjectMap.Entry<int[]> e : bySection.long2ObjectEntrySet()) {
			long node = e.getLongKey();
			int cx = SectionPos.x(node) - ox, cy = SectionPos.y(node) - minY, cz = SectionPos.z(node) - oz;
			if (cx < 0 || cx >= w || cy < 0 || cy >= height || cz < 0 || cz >= w) continue;
			int[] l = e.getValue();
			long cell = p + HEADER + ((long) (cz * height + cy) * w + cx) * 8;
			memPutInt(cell, n);
			memPutInt(cell + 4, l.length / 2);
			int bx = (SectionPos.x(node) << 4) - anchorX, by = (SectionPos.y(node) << 4) - anchorY, bz = (SectionPos.z(node) << 4) - anchorZ;
			for (int i = 0; i < l.length; i += 2, n++) {
				long q = p + lightsOffset + (long) n * LIGHT_SIZE;
				int packed = l[i], rgb = l[i + 1];
				memPutFloat(q, bx + (packed & 15) + 0.5f);
				memPutFloat(q + 4, by + (packed >> 4 & 15) + 0.5f);
				memPutFloat(q + 8, bz + (packed >> 8 & 15) + 0.5f);
				memPutFloat(q + 12, packed >> 12 & 15);
				memPutFloat(q + 16, (float) Math.pow((rgb >> 16 & 255) / 255f, 2.2));
				memPutFloat(q + 20, (float) Math.pow((rgb >> 8 & 255) / 255f, 2.2));
				memPutFloat(q + 24, (float) Math.pow((rgb & 255) / 255f, 2.2));
				memPutFloat(q + 28, 0);
			}
		}
		memPutInt(p, -r);
		memPutInt(p + 4, minY - camera.y());
		memPutInt(p + 8, -r);
		memPutInt(p + 12, n);
		memPutInt(p + 16, w);
		memPutInt(p + 20, height);
		memPutInt(p + 24, w);
		memPutInt(p + 28, 0);
		memPutLong(p + 32, buf.address() + lightsOffset);
		memPutLong(p + 40, 0);
		count = n;
		return n == 0 ? 0 : buf.address();
	}

	/** Resource packs changed: texture colours may have too. */
	public static void clearColours() {
		colours.clear();
	}

	static void destroy() {
		for (Buf b : buffers) if (b != null) b.free();
		bySection.clear();
	}
}
