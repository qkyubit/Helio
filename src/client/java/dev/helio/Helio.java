package dev.helio;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.blaze3d.platform.InputConstants;
import dev.helio.config.HelioConfig;
import dev.helio.config.HelioScreen;
import dev.helio.ngx.DlssLibraries;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.ngx.Ngx;
import dev.helio.ngx.NgxStartup;
import dev.helio.ngx.RayReconstruction;
import dev.helio.rt.PathTracer;
import dev.helio.rt.Scene;
import dev.helio.vk.GpuTimers;
import dev.helio.vk.HelioFeatures;
import dev.helio.vk.Memory;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class Helio implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("helio");

	public static VulkanDevice device;
	/** Device extensions for each feature were enabled. {@code dlss} additionally means NGX is up. */
	public static boolean rayTracing, dlssExtensions, dlss, reflex;
	/** Opacity micromaps for cutout geometry (config {@code opacityMicromaps}, read at startup). */
	public static boolean omm;
	public static NgxStartup.Caps ngxCaps;
	/** Why DLSS is or isn't running, for the settings screen and debug report. */
	public static String dlssStatus = "not started";

	private static final SystemToast.SystemToastId DLSS_TOAST = new SystemToast.SystemToastId(8000L);

	@Override
	public void onInitializeClient() {
		HelioConfig.load();
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("helio", "helio"));
		KeyMapping toggleRt = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.helio.toggle_rt", InputConstants.KEY_H, category));
		KeyMapping settings = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.helio.settings", InputConstants.UNKNOWN.getValue(), category));
		boolean flyTest = Boolean.getBoolean("helio.flyTest");
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			SelfCheck.tick(mc);
			Scene.tick(mc);
			// Dev stress test (-Dhelio.flyTest=true): fly east at 40 blocks/s so new sections stream in nonstop.
			if (flyTest && mc.player != null && mc.gui.screen() == null) {
				mc.player.getAbilities().flying = true;
				mc.player.setPos(mc.player.getX() + 2, Math.max(mc.player.getY(), 100), mc.player.getZ());
			}
			while (settings.consumeClick()) mc.gui.setScreen(new HelioScreen(mc.gui.screen()));
			while (toggleRt.consumeClick()) {
				HelioConfig c = HelioConfig.current;
				c.rayTracing = !c.rayTracing;
				c.preset = HelioConfig.Preset.CUSTOM;
				HelioConfig.save();
				mc.gui.hud.setOverlayMessage(Component.translatable(c.rayTracing ? "helio.rt.on" : "helio.rt.off"), false);
			}
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("helio", "perf_overlay"), PerfOverlay::render);
		LOG.info("Helio loaded");
	}

	public static void onDeviceCreated(VulkanDevice dev, FeatureSet enabled) {
		device = dev;
		rayTracing = enabled.contains(HelioFeatures.RAY_TRACING);
		dlssExtensions = enabled.contains(HelioFeatures.DLSS);
		reflex = enabled.contains(HelioFeatures.REFLEX);
		omm = rayTracing && enabled.contains(HelioFeatures.OPACITY_MICROMAP) && HelioConfig.current.opacityMicromaps;
		LOG.info("Vulkan device ready: ray tracing={}, DLSS extensions={}, Reflex={}, opacity micromaps={}", rayTracing, dlssExtensions, reflex, omm);
		if (rayTracing) {
			Memory.init(dev);
			Scene.init(dev.vkDevice());
		}
		if (!dlssExtensions) {
			dlssStatus = "GPU or driver lacks VK_NVX_binary_import / VK_NVX_image_view_handle";
		} else if (DlssLibraries.present(helioDir().resolve("ngx"))) {
			initNgx();
		} else if (!HelioConfig.current.downloadDlss) {
			dlssStatus = "DLSS libraries missing from " + helioDir().resolve("ngx") + " and downloading is off";
		} else {
			downloadDlss();
		}
	}

	public static Path helioDir() {
		return FabricLoader.getInstance().getGameDir().resolve("helio");
	}

	/** Render thread only. */
	private static void initNgx() {
		if (dlss) return;
		try {
			onBigStack(() -> ngxCaps = NgxStartup.run(device, helioDir()));
			dlss = true;
			dlssStatus = "running";
			LOG.info("DLSS: {}", ngxCaps);
		} catch (Throwable t) {
			LOG.error("NGX init failed; DLSS disabled", t);
			dlssStatus = "NGX init failed: " + t;
		}
	}

	/** First launch: fetch the DLSS libraries in the background, then bring NGX up on the render thread. */
	private static void downloadDlss() {
		dlssStatus = "downloading";
		Minecraft mc = Minecraft.getInstance();
		long totalMb = DlssLibraries.TOTAL_BYTES >> 20;
		Thread t = new Thread(() -> {
			long[] lastToast = {0};
			try {
				DlssLibraries.download(helioDir().resolve("ngx"), bytes -> {
					long now = System.nanoTime();
					if (now - lastToast[0] < 500_000_000L) return;
					lastToast[0] = now;
					mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), DLSS_TOAST, Component.translatable("helio.dlss.downloading"),
						Component.translatable("helio.dlss.progress", bytes >> 20, totalMb)));
				});
				mc.execute(() -> {
					initNgx();
					SystemToast.addOrUpdate(mc.gui.toastManager(), DLSS_TOAST, Component.translatable(dlss ? "helio.dlss.ready" : "helio.dlss.failed"), null);
				});
			} catch (Exception e) {
				LOG.error("DLSS download failed", e);
				mc.execute(() -> {
					dlssStatus = "download failed: " + e.getMessage();
					SystemToast.addOrUpdate(mc.gui.toastManager(), DLSS_TOAST, Component.translatable("helio.dlss.failed"),
						Component.translatable("helio.dlss.download_failed"));
				});
			}
		}, "Helio DLSS download");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * DLSS-G keeps Vulkan objects alive in static state; if they outlive the device, its exit-time
	 * destructors call into Vulkan layers the loader has already unloaded and segfault.
	 */
	public static void onDeviceClosing() {
		device.graphicsQueue().waitIdle();
		GpuTimers.destroy();
		if (dlss) {
			try {
				FrameGenSpike.release();
				RayReconstruction.release();
				Ngx.shutdown(device.vkDevice().address());
				LOG.info("NGX shut down");
			} catch (Throwable t) {
				LOG.error("NGX shutdown failed", t);
			}
		}
		if (rayTracing) {
			PathTracer.destroy();
			Scene.destroy();
			Memory.destroy();
		}
	}

	public interface NativeWork {
		void run() throws Throwable;
	}

	/**
	 * NGX init puts a 1 MB buffer on the stack; Java threads default to ~1 MB, so calling it on the
	 * render thread overflows and segfaults. Run such calls on a thread with a roomy stack while the
	 * caller waits (so Vulkan queue use stays single-threaded).
	 */
	public static void onBigStack(NativeWork work) throws Throwable {
		Throwable[] error = new Throwable[1];
		Thread t = new Thread(null, () -> {
			try {
				work.run();
			} catch (Throwable e) {
				error[0] = e;
			}
		}, "Helio NGX", 64L << 20);
		t.start();
		t.join();
		if (error[0] != null) throw error[0];
	}
}
