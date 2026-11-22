package dev.helio.lod;

import com.mojang.blaze3d.platform.NativeImage;
import dev.helio.mixin.SpriteContentsAccessor;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A LOD face's colour (sRGB, 0xRRGGBB): the average of the block's texels on that side (its model's quads facing
 * it, or all of them for plants and other shapes with none), each quad tinted as vanilla tints it in that biome
 * (its own tint sources, asked with a stand-in level whose every position is in that biome). Worker thread only.
 */
final class LodColors {
	private final Mapping mapping;
	private final RegistryAccess registries;
	private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();
	private final Map<TextureAtlasSprite, int[]> spriteAverages = new IdentityHashMap<>();
	private final Map<Integer, Biome> biomes = new java.util.HashMap<>();
	private Biome biome; // the stand-in level's
	private final BlockAndTintGetter level;

	LodColors(Mapping mapping, RegistryAccess registries) {
		this.mapping = mapping;
		this.registries = registries;
		cache.defaultReturnValue(-1);
		InvocationHandler h = (proxy, method, args) -> {
			if (method.getName().equals("getBlockTint") && args != null && args.length == 2 && args[1] instanceof ColorResolver r)
				return biome == null ? 0xFFFFFF : r.getColor(biome, ((BlockPos) args[0]).getX(), ((BlockPos) args[0]).getZ());
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
			if (method.getName().equals("getBlockState")) return Blocks.AIR.defaultBlockState();
			if (method.getName().equals("getFluidState")) return Blocks.AIR.defaultBlockState().getFluidState();
			Class<?> t = method.getReturnType();
			return t == int.class ? 0 : t == boolean.class ? false : t == float.class ? 0f : t == long.class ? 0L : t == double.class ? 0.0 : null;
		};
		level = (BlockAndTintGetter) Proxy.newProxyInstance(BlockAndTintGetter.class.getClassLoader(), new Class<?>[] {BlockAndTintGetter.class}, h);
	}

	/** dir: Direction ordinal (down, up, north, south, west, east). */
	int color(int state, int biomeId, int dir) {
		long key = (long) state << 24 | (long) biomeId << 3 | dir;
		int c = cache.get(key);
		if (c >= 0) return c;
		c = compute(mapping.stateOf(state), biomeId, Direction.values()[dir]);
		cache.put(key, c);
		return c;
	}

	private int compute(BlockState state, int biomeId, Direction dir) {
		biome = biomes.computeIfAbsent(biomeId, this::lookupBiome);
		var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42), parts);
		List<BakedQuad> quads = new ArrayList<>();
		for (BlockStateModelPart p : parts) quads.addAll(p.getQuads(dir));
		if (quads.isEmpty()) for (BlockStateModelPart p : parts) {
			quads.addAll(p.getQuads(null));
			for (Direction d : Direction.values()) quads.addAll(p.getQuads(d));
		}
		long r = 0, g = 0, b = 0, n = 0;
		if (quads.isEmpty()) { // fluids: no model; their particle sprite, water with its biome's colour (the fluid renderer's tint)
			int[] avg = average(model.particleMaterial().sprite());
			int tint = state.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && biome != null ? biome.getWaterColor() & 0xFFFFFF : tint(state, 0);
			return mul(avg, tint);
		}
		for (BakedQuad q : quads) {
			int[] avg = average(q.materialInfo().sprite());
			int c = mul(avg, q.materialInfo().isTinted() ? tint(state, q.materialInfo().tintIndex()) : 0xFFFFFF);
			r += (c >> 16 & 255) * (long) avg[3];
			g += (c >> 8 & 255) * (long) avg[3];
			b += (c & 255) * (long) avg[3];
			n += avg[3];
		}
		return n == 0 ? 0x808080 : (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	private Biome lookupBiome(int id) {
		String key = mapping.biomeKey(id);
		try {
			return registries.lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, Identifier.parse(key))).map(h -> h.value()).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private int tint(BlockState state, int index) {
		try {
			BlockTintSource s = Minecraft.getInstance().getBlockColors().getTintSource(state, index);
			return s == null ? 0xFFFFFF : s.colorInWorld(state, level, BlockPos.ZERO) & 0xFFFFFF;
		} catch (RuntimeException e) {
			return 0xFFFFFF;
		}
	}

	private static int mul(int[] avg, int tint) {
		return (avg[0] * (tint >> 16 & 255) / 255) << 16 | (avg[1] * (tint >> 8 & 255) / 255) << 8 | avg[2] * (tint & 255) / 255;
	}

	/** A sprite's first frame: mean colour of its mostly opaque texels, and how many there are (r, g, b, count). */
	private int[] average(TextureAtlasSprite sprite) {
		return spriteAverages.computeIfAbsent(sprite, s -> {
			NativeImage image = ((SpriteContentsAccessor) s.contents()).helio$originalImage();
			int w = Math.min(image.getWidth(), s.contents().width()), h = Math.min(image.getHeight(), s.contents().height());
			long r = 0, g = 0, b = 0;
			int n = 0;
			for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
				int argb = image.getPixel(x, y);
				if ((argb >>> 24) < 128) continue;
				r += argb >> 16 & 255;
				g += argb >> 8 & 255;
				b += argb & 255;
				n++;
			}
			return n == 0 ? new int[] {128, 128, 128, 0} : new int[] {(int) (r / n), (int) (g / n), (int) (b / n), n};
		});
	}
}
