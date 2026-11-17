package dev.helio.rt;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.helio.Helio;
import dev.helio.mixin.SpriteContentsAccessor;
import dev.helio.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LabPBR maps for the block atlas: a normal atlas ({@code _n}: normal XY, AO, height) and a specular atlas
 * ({@code _s}: smoothness, F0 / metal, porosity / SSS, emission), laid out exactly like the block atlas so the
 * path tracer reads them at the same UV. Rebuilt on every block-atlas upload (resource reloads, F3+T).
 * <p>
 * Where a pack has no map: flat normal, no AO, no height; specular all zero, which the path tracer reads as "no
 * data" (Helio's own material marks apply).
 * <p>
 * Animated sprites (water, lava, fire...): their maps' frames sit in strip textures, and every atlas tick copies
 * the current one in, on the same clock as vanilla's frames (the texture's {@code .mcmeta}, restarted with
 * vanilla's at each upload). ponytail: no interpolated frames; the map steps where vanilla blends.
 */
public final class PbrAtlas {
	/** Flat normal (128, 128), AO 1, no height; ARGB as NativeImage takes it. */
	private static final int FLAT_NORMAL = 0xFF8080FF;

	public static GpuTexture normal, specular;
	public static GpuTextureView normalView, specularView;
	/** Sprites that got a pack's map, last rebuild. */
	public static int normals, speculars;
	private static final List<Anim> anims = new ArrayList<>();

	/** An animated sprite's frame clock and its maps' frames (sprite-sized, stacked by image frame index). */
	private static final class Anim {
		final int x, y, w, h;
		final int[] index, time; // vanilla's frame list: image frame, ticks shown
		NativeImage normalFrames, specularFrames; // until uploaded
		GpuTexture normalStrip, specularStrip;
		int normalCount, specularCount; // frames in each strip, 0: not animated
		int frame, sub;

		Anim(int x, int y, int w, int h, int[] index, int[] time) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.index = index;
			this.time = time;
		}
	}

	private PbrAtlas() {}

	/** After the block atlas uploads (render thread). */
	public static void rebuild(TextureAtlas atlas) {
		if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
		GpuTexture base = atlas.getTexture();
		int w = base.getWidth(0), h = base.getHeight(0);
		Micromaps.atlasWidth = w; // sections compiled from here on size their micromaps by it
		Micromaps.atlasHeight = h;
		if (Helio.device == null) return;
		long start = System.nanoTime();
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		ConcurrentLinkedQueue<Anim> found = new ConcurrentLinkedQueue<>();
		try (NativeImage n = new NativeImage(w, h, false); NativeImage sp = new NativeImage(w, h, true)) {
			n.fillRect(0, 0, w, h, FLAT_NORMAL);
			AtomicInteger nCount = new AtomicInteger(), sCount = new AtomicInteger();
			// Sprites cover disjoint texels: safe to fill in parallel.
			((TextureAtlasAccessor) atlas).helio$texturesByName().values().parallelStream().forEach(sprite -> {
				Anim anim = sprite.contents().isAnimated() ? timeline(resources, sprite, w, h) : null;
				if (blit(resources, sprite, "_n", n, w, h, anim)) nCount.incrementAndGet();
				if (blit(resources, sprite, "_s", sp, w, h, anim)) sCount.incrementAndGet();
				if (anim != null && (anim.normalCount > 0 || anim.specularCount > 0)) found.add(anim);
			});
			normals = nCount.get();
			speculars = sCount.get();
			close();
			var device = Helio.device;
			normal = device.createTexture("Helio PBR normal atlas", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			specular = device.createTexture("Helio PBR specular atlas", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToTexture(normal, n);
			encoder.writeToTexture(specular, sp);
			normalView = device.createTextureView(normal, 0, 1);
			specularView = device.createTextureView(specular, 0, 1);
			for (Anim a : found) {
				a.normalStrip = upload(encoder, a.normalFrames, "normal");
				a.specularStrip = upload(encoder, a.specularFrames, "specular");
				a.normalFrames = a.specularFrames = null;
				anims.add(a);
			}
		} finally {
			for (Anim a : found) { // only still set if the upload didn't happen
				if (a.normalFrames != null) a.normalFrames.close();
				if (a.specularFrames != null) a.specularFrames.close();
			}
		}
		Helio.LOG.info("PBR atlases {}x{}: {} normal maps, {} specular maps, {} animated ({} ms)", w, h, normals, speculars,
			anims.size(), (System.nanoTime() - start) / 1_000_000);
	}

	/** Each atlas tick (render thread): advance the frame clocks, copy in frames that changed. */
	public static void tick(TextureAtlas atlas) {
		if (anims.isEmpty() || normal == null || !atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
		CommandEncoder encoder = null;
		for (Anim a : anims) {
			int was = a.index[a.frame];
			// As vanilla's SpriteContents.AnimationState.tick.
			if (++a.sub >= a.time[a.frame]) {
				a.frame = (a.frame + 1) % a.index.length;
				a.sub = 0;
			}
			int now = a.index[a.frame];
			if (now == was) continue;
			if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
			if (a.normalCount > 0) encoder.copyTextureToTexture(a.normalStrip, normal, 0, a.x, a.y, 0, now % a.normalCount * a.h, a.w, a.h);
			if (a.specularCount > 0) encoder.copyTextureToTexture(a.specularStrip, specular, 0, a.x, a.y, 0, now % a.specularCount * a.h, a.w, a.h);
		}
	}

	/** Vanilla's frame list for an animated sprite: its .mcmeta's frames, or every frame of the image in order. */
	private static Anim timeline(ResourceManager resources, TextureAtlasSprite sprite, int aw, int ah) {
		SpriteContents c = sprite.contents();
		NativeImage image = ((SpriteContentsAccessor) c).helio$originalImage();
		int count = Math.max(1, (image.getWidth() / c.width()) * (image.getHeight() / c.height()));
		AnimationMetadataSection meta = resources.getResource(c.name().withPath(p -> "textures/" + p + ".png")).flatMap(r -> {
			try {
				return r.metadata().getSection(AnimationMetadataSection.TYPE);
			} catch (Exception e) {
				return Optional.empty();
			}
		}).orElse(null);
		int defaultTime = meta == null ? 1 : meta.defaultFrameTime();
		List<AnimationFrame> frames = meta == null ? null : meta.frames().orElse(null);
		int size = frames == null ? count : frames.size();
		int[] index = new int[Math.max(1, size)], time = new int[index.length];
		for (int i = 0; i < size; i++) {
			index[i] = frames == null ? i : Math.floorMod(frames.get(i).index(), count);
			time[i] = Math.max(1, frames == null ? defaultTime : frames.get(i).timeOr(defaultTime));
		}
		if (size == 0) time[0] = 1;
		return new Anim(Math.round(sprite.getU0() * aw), Math.round(sprite.getV0() * ah), c.width(), c.height(), index, time);
	}

	/**
	 * The sprite's {@code <name><suffix>.png}, scaled (nearest) to the sprite and its first frame written at its atlas
	 * place; for an animated sprite whose map is animated too, all its frames into {@code anim}.
	 */
	private static boolean blit(ResourceManager resources, TextureAtlasSprite sprite, String suffix, NativeImage into, int aw, int ah, Anim anim) {
		Identifier name = sprite.contents().name();
		Optional<Resource> res = resources.getResource(name.withPath(p -> "textures/" + p + suffix + ".png"));
		if (res.isEmpty()) return false;
		try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
			SpriteContents c = sprite.contents();
			int sw = c.width(), sh = c.height();
			int x0 = Math.round(sprite.getU0() * aw), y0 = Math.round(sprite.getV0() * ah);
			// Frames laid out as in the sprite's own image (columns of the image's width over the sprite's).
			int cols = Math.max(1, ((SpriteContentsAccessor) c).helio$originalImage().getWidth() / sw);
			int fw = Math.max(1, src.getWidth() / cols), fh = Math.max(1, Math.min(src.getHeight(), fw * sh / sw));
			int frames = cols * (src.getHeight() / fh);
			if (suffix.equals("_n")) level(src);
			for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++)
				into.setPixel(x0 + x, y0 + y, src.getPixel(x * fw / sw, y * fh / sh));
			if (anim != null && frames > 1) {
				NativeImage strip = new NativeImage(sw, sh * frames, false);
				for (int f = 0; f < frames; f++) {
					int fx = f % cols * fw, fy = f / cols * fh;
					for (int y = 0; y < sh; y++) for (int x = 0; x < sw; x++)
						strip.setPixel(x, f * sh + y, src.getPixel(fx + x * fw / sw, fy + y * fh / sh));
				}
				if (suffix.equals("_n")) {
					anim.normalFrames = strip;
					anim.normalCount = frames;
				} else {
					anim.specularFrames = strip;
					anim.specularCount = frames;
				}
			}
			return true;
		} catch (Exception e) {
			Helio.LOG.warn("Couldn't read PBR map for {}{}: {}", name, suffix, e.toString());
			return false;
		}
	}

	/**
	 * Takes a normal map's average tilt out (its XY mean back to flat, in place). A block's map should average flat
	 * (it tiles); SPBR's netherrack leans 0.14 one way (x3 at Normal Map Strength 300%), so every netherrack face
	 * looked lit from the same side, and with Random Block Rotation each block leaned its own way, in patches.
	 */
	private static void level(NativeImage map) {
		long r = 0, g = 0, n = (long) map.getWidth() * map.getHeight();
		for (int y = 0; y < map.getHeight(); y++) for (int x = 0; x < map.getWidth(); x++) {
			int p = map.getPixel(x, y);
			r += p >> 16 & 255;
			g += p >> 8 & 255;
		}
		int dr = Math.round(128 - (float) r / n), dg = Math.round(128 - (float) g / n);
		if (dr == 0 && dg == 0) return;
		for (int y = 0; y < map.getHeight(); y++) for (int x = 0; x < map.getWidth(); x++) {
			int p = map.getPixel(x, y);
			int nr = Math.clamp((p >> 16 & 255) + dr, 0, 255), ng = Math.clamp((p >> 8 & 255) + dg, 0, 255);
			map.setPixel(x, y, p & 0xFF0000FF | nr << 16 | ng << 8);
		}
	}

	private static GpuTexture upload(CommandEncoder encoder, NativeImage frames, String what) {
		if (frames == null) return null;
		try (frames) {
			GpuTexture t = Helio.device.createTexture("Helio PBR " + what + " frames", 15, GpuFormat.RGBA8_UNORM,
				frames.getWidth(), frames.getHeight(), 1, 1);
			encoder.writeToTexture(t, frames);
			return t;
		}
	}

	private static void close() {
		if (normalView != null) normalView.close();
		if (specularView != null) specularView.close();
		if (normal != null) normal.close();
		if (specular != null) specular.close();
		normalView = specularView = null;
		normal = specular = null;
		for (Anim a : anims) {
			if (a.normalStrip != null) a.normalStrip.close();
			if (a.specularStrip != null) a.specularStrip.close();
		}
		anims.clear();
	}
}
