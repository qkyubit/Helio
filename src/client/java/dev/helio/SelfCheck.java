package dev.helio;

import dev.helio.config.HelioConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;

/**
 * Tells the player once, on the title screen, when Helio can't run (wrong backend, GPU or driver), and builds
 * the debug report the settings screen copies. Missing features never crash: each pass just stays off.
 */
public final class SelfCheck {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(15000L);
	private static boolean shown;

	private SelfCheck() {}

	public static void tick(Minecraft mc) {
		if (shown || !(mc.gui.screen() instanceof TitleScreen || mc.level != null)) return;
		shown = true;
		String problem = problem();
		if (problem != null) {
			Helio.LOG.warn("Self-check: {}", problem);
			SystemToast.add(mc.gui.toastManager(), TOAST, Component.translatable("helio.check.title"), Component.translatable(problem));
		}
	}

	/** Translation key of the most important thing that's missing, or null if everything works. */
	private static String problem() {
		if (Helio.device == null) return "helio.check.no_vulkan";
		if (!Helio.rayTracing) return "helio.check.no_rt";
		if (!Helio.dlssExtensions) return "helio.check.no_dlss";
		if (!Helio.dlss && !Helio.dlssStatus.equals("downloading")) return "helio.check.dlss_failed";
		return null;
	}

	public static String debugReport() {
		StringBuilder r = new StringBuilder("Helio debug report\n");
		FabricLoader fl = FabricLoader.getInstance();
		line(r, "Helio", fl.getModContainer("helio").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		line(r, "Minecraft", fl.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		line(r, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version"));
		line(r, "Java", System.getProperty("java.vm.vendor") + " " + Runtime.version());
		if (Helio.device == null) {
			line(r, "Backend", "not Vulkan");
		} else {
			var info = Helio.device.getDeviceInfo();
			line(r, "GPU", info.name() + " (" + info.vendorName() + ")");
			line(r, "Driver", info.driverInfo());
		}
		line(r, "Ray tracing extensions", Helio.rayTracing);
		line(r, "DLSS extensions", Helio.dlssExtensions);
		line(r, "DLSS", Helio.dlssStatus);
		line(r, "DLSS features", Helio.ngxCaps);
		line(r, "Reflex", Helio.reflex);
		r.append("Config: ").append(HelioConfig.toJson()).append('\n');
		r.append("Mods:\n");
		fl.getAllMods().stream()
			.filter(m -> m.getContainingMod().isEmpty()) // skip jar-in-jar modules (Fabric API's parts etc.)
			.map(ModContainer::getMetadata)
			.sorted(Comparator.comparing(m -> m.getId()))
			.forEach(m -> r.append("  ").append(m.getId()).append(' ').append(m.getVersion().getFriendlyString()).append('\n'));
		return r.toString();
	}

	private static void line(StringBuilder r, String key, Object value) {
		r.append(key).append(": ").append(value).append('\n');
	}
}
