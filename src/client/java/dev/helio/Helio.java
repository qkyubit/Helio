package dev.helio;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.ngx.Ngx;
import dev.helio.ngx.NgxStartup;
import dev.helio.vk.HelioFeatures;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Helio implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("helio");

	public static VulkanDevice device;
	public static boolean rayTracing, dlss, reflex;
	public static NgxStartup.Caps ngxCaps;

	@Override
	public void onInitializeClient() {
		LOG.info("Helio loaded");
	}

	public static void onDeviceCreated(VulkanDevice dev, FeatureSet enabled) {
		device = dev;
		rayTracing = enabled.contains(HelioFeatures.RAY_TRACING);
		dlss = enabled.contains(HelioFeatures.DLSS);
		reflex = enabled.contains(HelioFeatures.REFLEX);
		LOG.info("Vulkan device ready: ray tracing={}, DLSS extensions={}, Reflex={}", rayTracing, dlss, reflex);
		if (dlss) {
			try {
				onBigStack(() -> ngxCaps = NgxStartup.run(dev, FabricLoader.getInstance().getGameDir().resolve("helio")));
				LOG.info("DLSS: {}", ngxCaps);
			} catch (Throwable t) {
				LOG.error("NGX init failed; DLSS disabled", t);
				dlss = false;
			}
		}
	}

	/**
	 * DLSS-G keeps Vulkan objects alive in static state; if they outlive the device, its exit-time
	 * destructors call into Vulkan layers the loader has already unloaded and segfault.
	 */
	public static void onDeviceClosing() {
		if (!dlss) return;
		try {
			device.graphicsQueue().waitIdle();
			FrameGenSpike.release();
			Ngx.shutdown(device.vkDevice().address());
			LOG.info("NGX shut down");
		} catch (Throwable t) {
			LOG.error("NGX shutdown failed", t);
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
