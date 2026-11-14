package dev.helio.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.helio.Helio;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings, saved as config/helio.json. Picking a preset copies its values into the fields; changing a field
 * afterwards switches the preset to {@link Preset#CUSTOM}, so presets are a starting point, not a lock.
 * <p>
 * Fields are added as the features behind them land (upscaling and RR in M4, GI bounces in M3, ...).
 */
public final class HelioConfig {
	public enum Preset { OFF, PERFORMANCE, BALANCED, QUALITY, ULTRA, PHOTO, CUSTOM }

	public enum ReflexMode { OFF, ON, BOOST }

	/**
	 * PATH_TRACED: everything traced (soft shadows, block-light shadows, bounce light; cleaned up by RR).
	 * HYBRID: ray-traced sun/moon shadows and reflections only, vanilla-style ambient and block light from
	 * light levels; no random sampling, so no denoiser needed. Much cheaper.
	 */
	public enum Lighting { PATH_TRACED, HYBRID }

	/** Render size as a fraction of the window's, and NGX's PerfQualityValue for it. DLAA: native, anti-aliasing only. */
	public enum DlssQuality {
		DLAA(1f, 5), QUALITY(1 / 1.5f, 2), BALANCED(1 / 1.72f, 1), PERFORMANCE(0.5f, 0), ULTRA_PERFORMANCE(1 / 3f, 3);

		public final float scale;
		public final int perfQuality;

		DlssQuality(float scale, int perfQuality) {
			this.scale = scale;
			this.perfQuality = perfQuality;
		}
	}

	public enum DebugView { OFF, INSTANCES, ALBEDO, NORMALS, TRIANGLE_DENSITY, MATERIALS, PBR_NORMAL, PBR_SPECULAR }

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("helio.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static HelioConfig current = new HelioConfig();

	public Preset preset = Preset.BALANCED;
	/** Master switch for every ray-traced effect; the toggle key flips it. */
	public boolean rayTracing = true;
	/** Chunks around the player that rays can hit, capped by the render distance. */
	public int rayTracingDistance = 12;
	public Lighting lighting = Lighting.PATH_TRACED;
	/** How much DLSS upscales the traced picture (Super Resolution, or Ray Reconstruction for path tracing). */
	public DlssQuality dlssQuality = DlssQuality.BALANCED;
	/** Presented frames per rendered frame: 1 = off, 2 = DLSS frame generation 2x, up to 1 + driver max for MFG. */
	public int frameGeneration = 2;
	public ReflexMode reflex = ReflexMode.ON;
	public boolean perfOverlay = false;
	public DebugView debugView = DebugView.OFF;
	/** Average frames while the picture is still: a clean reference image. Moving entities smear. */
	public boolean accumulate = false;
	/** DLSS Ray Reconstruction cleans up a frame of random samples (soft shadows, bounce light); off without NGX. */
	public boolean rayReconstruction = true;
	/** Least light in caves and enclosed spaces (no sky light), in percent of the open-air floor from Brightness. */
	public int caveBrightness = 25;
	/**
	 * Not in the UI; applies at startup. Bounce rays through foliage trace about 30% faster (6.4 vs 9.5 ms
	 * in a dense forest) for about 40% more scene memory (1219 vs 869 MB). Single shadow rays don't care.
	 */
	public boolean opacityMicromaps = true;
	/** Fetch the DLSS libraries from NVIDIA's GitHub on first launch. Off: drop them into helio/ngx/ by hand. */
	public boolean downloadDlss = true;

	public void applyPreset(Preset p) {
		preset = p;
		switch (p) {
			case OFF -> { rayTracing = false; frameGeneration = 1; reflex = ReflexMode.ON; }
			case PERFORMANCE -> { rayTracing = true; lighting = Lighting.HYBRID; dlssQuality = DlssQuality.PERFORMANCE; frameGeneration = 2; reflex = ReflexMode.BOOST; }
			// Heavier presets render fewer frames, so they generate more (multi frame generation where offered).
			case BALANCED -> { rayTracing = true; lighting = Lighting.PATH_TRACED; dlssQuality = DlssQuality.BALANCED; frameGeneration = 3; reflex = ReflexMode.ON; }
			case QUALITY -> { rayTracing = true; lighting = Lighting.PATH_TRACED; dlssQuality = DlssQuality.QUALITY; frameGeneration = 3; reflex = ReflexMode.ON; }
			case ULTRA -> { rayTracing = true; lighting = Lighting.PATH_TRACED; dlssQuality = DlssQuality.DLAA; frameGeneration = 4; reflex = ReflexMode.ON; }
			// Screenshots: no generated frames (they're slightly soft), no latency trade-offs needed.
			case PHOTO -> { rayTracing = true; lighting = Lighting.PATH_TRACED; dlssQuality = DlssQuality.DLAA; frameGeneration = 1; reflex = ReflexMode.OFF; }
			case CUSTOM -> {}
		}
	}

	public static void load() {
		if (Files.exists(FILE)) {
			try {
				HelioConfig c = GSON.fromJson(Files.readString(FILE), HelioConfig.class);
				if (c != null) current = c;
			} catch (Exception e) {
				Helio.LOG.error("Can't read {}, using defaults", FILE, e);
			}
		}
		if (current.preset == null) current.preset = Preset.BALANCED;
		if (current.reflex == null) current.reflex = ReflexMode.ON;
		if (current.debugView == null) current.debugView = DebugView.OFF;
		current.frameGeneration = Math.clamp(current.frameGeneration, 1, 6);
		current.rayTracingDistance = Math.clamp(current.rayTracingDistance, 2, 32);
	}

	public static String toJson() {
		return GSON.toJson(current);
	}

	public static void save() {
		try {
			Files.writeString(FILE, toJson());
		} catch (IOException e) {
			Helio.LOG.error("Can't write {}", FILE, e);
		}
	}
}
