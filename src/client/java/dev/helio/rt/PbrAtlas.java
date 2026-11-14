package dev.helio.rt;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.helio.Helio;
import dev.helio.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LabPBR maps for the block atlas: a normal atlas ({@code _n}: normal XY, AO, height) and a specular atlas
 * ({@code _s}: smoothness, F0 / metal, porosity / SSS, emission), laid out exactly like the block atlas so the
 * path tracer reads them at the same UV. Rebuilt on every block-atlas upload (resource reloads, F3+T).
 * <p>
 * Where a pack has no map: flat normal, no AO, no height; specular all zero, which the path tracer reads as "no
 * data" (Helio's own material marks apply).
 */
public final class PbrAtlas {
	/** Flat normal (128, 128), AO 1, no height; ARGB as NativeImage takes it. */
	private static final int FLAT_NORMAL = 0xFFFF8080;

	public static GpuTexture normal, specular;
	public static GpuTextureView normalView, specularView;
	/** Sprites that got a pack's map, last rebuild. */
	public static int normals, speculars;

	private PbrAtlas() {}

	/** After the block atlas uploads (render thread). */
	public static void rebuild(TextureAtlas atlas) {
		if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS) || Helio.device == null) return;
		long start = System.nanoTime();
		GpuTexture base = atlas.getTexture();
		int w = base.getWidth(0), h = base.getHeight(0);
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		try (NativeImage n = new NativeImage(w, h, false); NativeImage sp = new NativeImage(w, h, true)) {
			n.fillRect(0, 0, w, h, FLAT_NORMAL);
			AtomicInteger nCount = new AtomicInteger(), sCount = new AtomicInteger();
			// Sprites cover disjoint texels: safe to fill in parallel.
			((TextureAtlasAccessor) atlas).helio$texturesByName().values().parallelStream().forEach(sprite -> {
				if (blit(resources, sprite, "_n", n, w, h)) nCount.incrementAndGet();
				if (blit(resources, sprite, "_s", sp, w, h)) sCount.incrementAndGet();
			});
			normals = nCount.get();
			speculars = sCount.get();
			close();
			var device = Helio.device;
			normal = device.createTexture("Helio PBR normal atlas", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			specular = device.createTexture("Helio PBR specular atlas", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToTexture(normal, n);
			encoder.writeToTexture(specular, sp);
			normalView = device.createTextureView(normal, 0, 1);
			specularView = device.createTextureView(specular, 0, 1);
		}
		Helio.LOG.info("PBR atlases {}x{}: {} normal maps, {} specular maps ({} ms)", w, h, normals, speculars,
			(System.nanoTime() - start) / 1_000_000);
	}

	/** The sprite's {@code <name><suffix>.png}, scaled (nearest) to the sprite and written at its atlas place. */
	private static boolean blit(ResourceManager resources, TextureAtlasSprite sprite, String suffix, NativeImage into, int aw, int ah) {
		Identifier name = sprite.contents().name();
		Optional<Resource> res = resources.getResource(name.withPath(p -> "textures/" + p + suffix + ".png"));
		if (res.isEmpty()) return false;
		try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
			int sw = sprite.contents().width(), sh = sprite.contents().height();
			int x0 = Math.round(sprite.getU0() * aw), y0 = Math.round(sprite.getV0() * ah);
			// Animated maps are strips like their sprite: the first frame is the top, as wide as the image.
			int fw = src.getWidth(), fh = Math.min(src.getHeight(), Math.max(1, fw * sh / sw));
			for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++)
				into.setPixel(x0 + x, y0 + y, src.getPixel(x * fw / sw, y * fh / sh));
			return true;
		} catch (Exception e) {
			Helio.LOG.warn("Couldn't read PBR map for {}{}: {}", name, suffix, e.toString());
			return false;
		}
	}

	private static void close() {
		if (normalView != null) normalView.close();
		if (specularView != null) specularView.close();
		if (normal != null) normal.close();
		if (specular != null) specular.close();
		normalView = specularView = null;
		normal = specular = null;
	}
}
