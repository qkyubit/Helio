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

	public enum DebugView { OFF, INSTANCES, ALBEDO, NORMALS, TRIANGLE_DENSITY }

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("helio.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static HelioConfig current = new HelioConfig();

	public Preset preset = Preset.BALANCED;
	/** Master switch for every ray-traced effect; the toggle key flips it. */
	public boolean rayTracing = true;
	/** Chunks around the player that rays can hit, capped by the render distance. */
	public int rayTracingDistance = 12;
	/** Presented frames per rendered frame: 1 = off, 2 = DLSS frame generation 2x, up to 1 + driver max for MFG. */
	public int frameGeneration = 2;
	public ReflexMode reflex = ReflexMode.ON;
	public boolean perfOverlay = false;
	public DebugView debugView = DebugView.OFF;
	/** Average frames while the picture is still: a clean reference image. Moving entities smear. */
	public boolean accumulate = false;
	/**
	 * Not in the UI; applies at startup. Off by default: for one shadow ray per pixel they saved nothing
	 * (0.78 vs 0.75 ms) and doubled scene memory (1256 vs 628 MB). Re-measure with M3's bounce rays.
	 */
	public boolean opacityMicromaps = false;
	/** Fetch the DLSS libraries from NVIDIA's GitHub on first launch. Off: drop them into helio/ngx/ by hand. */
	public boolean downloadDlss = true;

	public void applyPreset(Preset p) {
		preset = p;
		switch (p) {
			case OFF -> { rayTracing = false; frameGeneration = 1; reflex = ReflexMode.ON; }
			case PERFORMANCE -> { rayTracing = true; frameGeneration = 2; reflex = ReflexMode.BOOST; }
			// ponytail: Balanced/Quality/Ultra only differ once M3/M4 add ray budgets and upscaling modes.
			case BALANCED, QUALITY, ULTRA -> { rayTracing = true; frameGeneration = 2; reflex = ReflexMode.ON; }
			// Screenshots: no generated frames (they're slightly soft), no latency trade-offs needed.
			case PHOTO -> { rayTracing = true; frameGeneration = 1; reflex = ReflexMode.OFF; }
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
