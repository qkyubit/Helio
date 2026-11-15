package dev.helio.vk;

import dev.helio.Helio;
import dev.helio.ngx.FrameGenSpike;
import org.lwjgl.vulkan.VkDevice;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.KHRPresentWait.vkWaitForPresentKHR;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * When each present reached the screen (VK_KHR_present_wait on its present ID), for frame generation's pacing
 * (M7 step 8). Frame generation numbers its presents (0, 1, 2... across frames); the pacer presents one only once
 * the one {@link FrameGenSpike} keeps ahead of it is on screen, so the display's own refresh spaces them, and the
 * limiter times frames from the last one shown.
 * <p>
 * Bench (config/helio-bench.txt): logs every few seconds how many refreshes apart real frames were shown (at n
 * presents per frame on a fixed refresh, smooth is always n) and where the latency from frame start goes.
 */
public final class DisplayClock {
	private record Shown(long swapchain, long id, long index, long frameStart, long done, long presentCall, int presents, long slot) {}

	private static final int RING = 256;
	private static final ConcurrentLinkedQueue<Shown> queue = new ConcurrentLinkedQueue<>();
	/** Per present index % RING: the index, and when it was shown. */
	private static final AtomicLongArray shownIndex = new AtomicLongArray(RING), shownNanos = new AtomicLongArray(RING);
	/** The latest present seen on screen. */
	public static volatile long lastShownIndex = -1, lastShownNanos;
	/**
	 * Shortest time from a present call to the screen lately (the compositor's deadline before a refresh): a present
	 * made at least this long before a refresh is shown at it. 0 until measured.
	 */
	public static volatile long presentLatencyNanos;
	private static final long[] recentLatency = new long[64];
	private static int recentCount;
	private static volatile VkDevice device;
	/** Bench: log the stats. */
	public static volatile boolean logging;
	private static Thread thread;
	private static final long[] slotError = new long[31];

	private DisplayClock() {}

	/** Whether presents can be timed (VK_KHR_present_wait on the device). */
	public static boolean available(VkDevice dev) {
		return dev != null && dev.getCapabilities().VK_KHR_present_wait;
	}

	/**
	 * A present went out. index: frame generation's present number, or -1 (a frame's own present without it).
	 * frameStart, done: when its frame started and its GPU work finished (0: not a real frame / unknown).
	 */
	public static void presented(VkDevice dev, long swapchain, long id, long index, long frameStart, long done, int presentsPerFrame) {
		presented(dev, swapchain, id, index, frameStart, done, presentsPerFrame, 0);
	}

	/** slot: when the pacer meant it to reach the screen (0: not clocked). */
	public static void presented(VkDevice dev, long swapchain, long id, long index, long frameStart, long done, int presentsPerFrame, long slot) {
		if (id == 0 || !available(dev)) return;
		if (index < 0 && !logging) return;
		device = dev;
		if (thread == null) {
			thread = new Thread(DisplayClock::run, "Helio display clock");
			thread.setDaemon(true);
			thread.start();
		}
		queue.add(new Shown(swapchain, id, index, frameStart, done, System.nanoTime(), presentsPerFrame, slot));
	}

	/**
	 * Render thread, before the swapchain is destroyed: stop waiting on its presents. Waiting on a destroyed one
	 * crashed the driver (SIGFPE in libnvidia-glcore, stress test toggling fullscreen with frame generation on).
	 */
	public static void forgetSwapchain() {
		synchronized (FrameGenSpike.QUEUE_LOCK) {
			queue.clear();
		}
	}

	/** When present {@code index} was shown, or 0 if not (yet). */
	public static long shownAt(long index) {
		int i = (int) (index % RING);
		return shownIndex.get(i) == index ? shownNanos.get(i) : 0;
	}

	private static void run() {
		try {
			loop();
		} catch (Throwable t) {
			Helio.LOG.error("Display clock stopped", t);
		}
	}

	private static void loop() {
		long[] gaps = new long[6];
		long[] latency = new long[4096], toDone = new long[4096], toPresent = new long[4096], toScreen = new long[4096];
		int samples = 0;
		long lastReal = 0, windowStart = System.nanoTime();
		while (true) {
			Shown s = queue.peek();
			if (s == null) {
				LockSupport.parkNanos(200_000);
				continue;
			}
			int r;
			synchronized (FrameGenSpike.QUEUE_LOCK) { // the swapchain wants external synchronisation
				if (queue.peek() != s) continue; // forgotten meanwhile: its swapchain is gone
				r = vkWaitForPresentKHR(device, s.swapchain, s.id, 0);
			}
			long now = System.nanoTime();
			if (r != VK_SUCCESS) {
				if (now - s.presentCall > 500_000_000L) {
					queue.poll(); // swapchain gone or the present was dropped
					lastReal = 0;
				} else LockSupport.parkNanos(100_000);
				continue;
			}
			queue.poll();
			// Only presents made with nothing ahead of them on the way to the screen: a queue counted in kept the
			// pacer presenting that much earlier, and the queue never drained (70 ms frame start -> screen at 3x).
			long prevShown = s.index > 0 ? shownAt(s.index - 1) : 0;
			if (prevShown > 0 && s.presentCall >= prevShown) recentLatency[recentCount++ % recentLatency.length] = now - s.presentCall;
			if (recentCount >= 16) {
				long min = Long.MAX_VALUE;
				for (int j = 0; j < Math.min(recentCount, recentLatency.length); j++) min = Math.min(min, recentLatency[j]);
				presentLatencyNanos = min;
			}
			if (s.index >= 0) {
				int i = (int) (s.index % RING);
				shownNanos.set(i, now);
				shownIndex.set(i, s.index);
				lastShownNanos = now;
				lastShownIndex = s.index;
			}
			if (logging && s.slot > 0) slotError[(int) Math.max(0, Math.min(slotError.length - 1, Math.round((now - s.slot) / 1e6) + 10))]++;
			if (!logging || s.frameStart == 0) continue;
			double refresh = Math.max(1, FrameGenSpike.refreshNanos);
			if (lastReal != 0) gaps[(int) Math.min(5, Math.round((now - lastReal) / refresh))]++;
			lastReal = now;
			if (samples < latency.length) {
				toDone[samples] = s.done > 0 ? s.done - s.frameStart : 0;
				toPresent[samples] = s.presentCall - (s.done > 0 ? s.done : s.frameStart);
				toScreen[samples] = now - s.presentCall;
				latency[samples++] = now - s.frameStart;
			}
			if (now - windowStart > 3_000_000_000L && samples > 0) {
				Helio.LOG.info("Present probe ({} presents/frame): real frames shown 0/1/2/3/4/5+ refreshes apart {}, latency frame start -> screen ms median {} p95 {}",
					s.presents, Arrays.toString(gaps), median(latency, samples, 50), median(latency, samples, 95));
				Helio.LOG.info("Present probe: median ms frame start -> GPU done {}, -> present call {}, -> screen {}",
					median(toDone, samples, 50), median(toPresent, samples, 50), median(toScreen, samples, 50));
				Helio.LOG.info("Present probe: shown minus planned, ms -10..+20: {}; present -> screen min {} ms", Arrays.toString(slotError),
					String.format("%.1f", presentLatencyNanos / 1e6));
				Arrays.fill(slotError, 0);
				Arrays.fill(gaps, 0);
				samples = 0;
				windowStart = now;
			}
		}
	}

	private static String median(long[] a, int n, int percentile) {
		long[] c = Arrays.copyOf(a, n);
		Arrays.sort(c);
		return String.format("%.1f", c[Math.min(n - 1, n * percentile / 100)] / 1e6);
	}
}
