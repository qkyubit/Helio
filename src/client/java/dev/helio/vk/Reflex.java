package dev.helio.vk;

import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.NVLowLatency2.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * NVIDIA Reflex through VK_NV_low_latency2: low-latency mode on Mojang's swapchain, a driver-paced sleep at
 * the start of each frame, and simulation / render-submit / present markers. Each game frame's real present
 * carries its frame ID (VK_KHR_present_id) so the driver can tie markers to it. Mode comes from
 * {@link HelioConfig#reflex} and is re-applied when it changes.
 */
public final class Reflex {
	private static VkDevice device;
	private static long swapchain, timeline, frameId, lastPresentId;
	private static boolean inFrame;
	private static HelioConfig.ReflexMode appliedMode;

	private Reflex() {}

	public static boolean active() {
		return Helio.reflex && swapchain != 0;
	}

	/** Pointer to chain into VkSwapchainCreateInfoKHR, or 0 if Reflex is unavailable. */
	public static long swapchainPNext(MemoryStack s, long existing) {
		if (!Helio.reflex) return existing;
		return VkSwapchainLatencyCreateInfoNV.calloc(s).sType$Default().pNext(existing).latencyModeEnable(true).address();
	}

	public static void onSwapchainCreated(VkDevice dev, long handle) {
		if (!Helio.reflex) return;
		device = dev;
		swapchain = handle;
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (timeline == 0) {
				LongBuffer h = s.mallocLong(1);
				vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(s).sType$Default()
					.pNext(VkSemaphoreTypeCreateInfo.calloc(s).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(frameId)), null, h);
				timeline = h.get(0);
			}
		}
		applySleepMode();
		Helio.LOG.info("Reflex enabled on swapchain 0x{}", Long.toHexString(handle));
	}

	/**
	 * With vsync the game is display-bound: frames queue in the swapchain and Reflex's sleep alone can't drain
	 * them. Like NVIDIA's Reflex+VSync auto cap, also cap rendering just under refresh / presents-per-frame.
	 */
	private static void applySleepMode() {
		HelioConfig.ReflexMode mode = HelioConfig.current.reflex;
		boolean lowLatency = mode != HelioConfig.ReflexMode.OFF;
		int intervalUs = 0;
		if (lowLatency) {
			float refresh = refreshRate();
			if (refresh > 0) intervalUs = (int) (1_000_000 / (refresh / presentsPerFrame) * 1.03f);
		}
		appliedPresentsPerFrame = presentsPerFrame;
		appliedMode = mode;
		try (MemoryStack s = MemoryStack.stackPush()) {
			int r = vkSetLatencySleepModeNV(device, swapchain, VkLatencySleepModeInfoNV.calloc(s).sType$Default()
				.lowLatencyMode(lowLatency).lowLatencyBoost(mode == HelioConfig.ReflexMode.BOOST).minimumIntervalUs(intervalUs));
			if (r != VK_SUCCESS) Helio.LOG.warn("vkSetLatencySleepModeNV returned {}", r);
		}
		Helio.LOG.info("Reflex {} (frame interval cap {} us, {} presents per rendered frame)", mode, intervalUs, presentsPerFrame);
	}

	private static int presentsPerFrame = 1, appliedPresentsPerFrame = 1;

	/** Called by frame generation: 1 + generated frames per rendered frame. */
	public static void setPresentsPerFrame(int n) {
		presentsPerFrame = n;
	}

	private static float refreshRate() {
		int display = org.lwjgl.sdl.SDLVideo.SDL_GetDisplayForWindow(net.minecraft.client.Minecraft.getInstance().getWindow().handle());
		var mode = display == 0 ? null : org.lwjgl.sdl.SDLVideo.SDL_GetCurrentDisplayMode(display);
		return mode == null ? 0 : mode.refresh_rate();
	}

	/** Start of Minecraft's frame (before input and ticks). */
	public static void frameStart() {
		if (!active()) return;
		if (presentsPerFrame != appliedPresentsPerFrame || HelioConfig.current.reflex != appliedMode) applySleepMode();
		frameId++;
		if (appliedMode != HelioConfig.ReflexMode.OFF) {
			try (MemoryStack s = MemoryStack.stackPush()) {
				vkLatencySleepNV(device, swapchain, VkLatencySleepInfoNV.calloc(s).sType$Default().signalSemaphore(timeline).value(frameId));
				vkWaitSemaphores(device, VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
					.pSemaphores(s.longs(timeline)).pValues(s.longs(frameId)), 100_000_000L);
			}
		}
		inFrame = true;
		marker(VK_LATENCY_MARKER_SIMULATION_START_NV);
		marker(VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
	}

	public static void renderStart() {
		marker(VK_LATENCY_MARKER_SIMULATION_END_NV);
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	public static void submitted() {
		marker(VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
	}

	public static void presentStart() {
		marker(VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	public static void presentEnd() {
		marker(VK_LATENCY_MARKER_PRESENT_END_NV);
		inFrame = false;
	}

	/**
	 * Present ID for the present showing this frame's real image (0 = none). Loading screens can present
	 * more than once per frame; IDs must strictly increase, so repeats go without one.
	 */
	public static long presentId() {
		if (!active() || frameId <= lastPresentId) return 0;
		return lastPresentId = frameId;
	}

	private static void marker(int marker) {
		if (!active() || !inFrame) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			vkSetLatencyMarkerNV(device, swapchain, VkSetLatencyMarkerInfoNV.calloc(s).sType$Default().presentID(frameId).marker(marker));
		}
	}

	/** Median input sample -> GPU render end in ms over the driver's recent frame reports (robust to hitches), or NaN. */
	public static double medianLatencyMs() {
		if (!active()) return Double.NaN;
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkLatencyTimingsFrameReportNV.Buffer reports = VkLatencyTimingsFrameReportNV.calloc(64, s);
			for (VkLatencyTimingsFrameReportNV r : reports) r.sType$Default();
			vkGetLatencyTimingsNV(device, swapchain, VkGetLatencyMarkerInfoNV.calloc(s).sType$Default().pTimings(reports));
			long[] us = new long[64];
			int n = 0;
			for (VkLatencyTimingsFrameReportNV r : reports) {
				if (r.inputSampleTimeUs() == 0 || r.gpuRenderEndTimeUs() <= r.inputSampleTimeUs()) continue;
				us[n++] = r.gpuRenderEndTimeUs() - r.inputSampleTimeUs();
			}
			if (n == 0) return Double.NaN;
			java.util.Arrays.sort(us, 0, n);
			return us[n / 2] / 1000.0;
		}
	}
}
