package dev.helio.vk;

import dev.helio.Helio;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Per-pass GPU timestamps for the performance overlay. Each pass brackets its commands with
 * {@link #begin}/{@link #end}; results are read {@value #RING} frames later (Mojang keeps at most two
 * submits in flight) and smoothed. Costs a few timestamp writes per frame, so it's always on.
 */
public final class GpuTimers {
	public enum Pass { SCENE_BUILD, PATH_TRACE, DLSS, FG_MOTION, DLSS_G, LOD }

	private static final int RING = 4, PASSES = Pass.values().length;
	private static final double SMOOTHING = 0.05;

	private static long pool;
	private static double nsPerTick;
	private static long frame;
	/** Bit per pass that wrote both timestamps in each ring slot. */
	private static final int[] written = new int[RING];
	private static final double[] avgMs = new double[PASSES];
	private static final long[] lastRun = new long[PASSES];

	private GpuTimers() {}

	/** Start of each frame, on the render thread: harvest the slot about to be reused, then reset it. */
	public static void newFrame() {
		if (Helio.device == null) return;
		var vk = Helio.device.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (pool == 0) {
				LongBuffer h = s.mallocLong(1);
				if (vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(s).sType$Default()
					.queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * PASSES * 2), null, h) != VK_SUCCESS) return;
				pool = h.get(0);
				vkResetQueryPool(vk, pool, 0, RING * PASSES * 2);
				nsPerTick = Helio.device.getDeviceInfo().timestampPeriod();
			}
			frame++;
			int slot = (int) (frame % RING);
			LongBuffer ts = s.mallocLong(2);
			for (int p = 0; p < PASSES; p++) {
				if ((written[slot] & (1 << p)) == 0) continue;
				int q = (slot * PASSES + p) * 2;
				if (vkGetQueryPoolResults(vk, pool, q, 2, ts, 8, VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) continue;
				double ms = (ts.get(1) - ts.get(0)) * nsPerTick / 1e6;
				avgMs[p] = avgMs[p] == 0 ? ms : avgMs[p] + (ms - avgMs[p]) * SMOOTHING;
			}
			written[slot] = 0;
			vkResetQueryPool(vk, pool, slot * PASSES * 2, PASSES * 2);
		}
	}

	public static void begin(VkCommandBuffer cmd, Pass pass) {
		stamp(cmd, pass, 0);
	}

	public static void end(VkCommandBuffer cmd, Pass pass) {
		stamp(cmd, pass, 1);
		written[(int) (frame % RING)] |= 1 << pass.ordinal();
		lastRun[pass.ordinal()] = frame;
	}

	private static void stamp(VkCommandBuffer cmd, Pass pass, int which) {
		if (pool == 0) return;
		int q = ((int) (frame % RING) * PASSES + pass.ordinal()) * 2 + which;
		// ALL_COMMANDS: the start stamp lands once prior work is done, the end stamp once the pass is.
		KHRSynchronization2.vkCmdWriteTimestamp2KHR(cmd, KHRSynchronization2.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, pool, q);
	}

	/** Smoothed GPU time of a pass in ms, or NaN if it hasn't run in the last few frames. */
	public static double ms(Pass pass) {
		int p = pass.ordinal();
		if (frame - lastRun[p] > RING * 2) {
			avgMs[p] = 0;
			return Double.NaN;
		}
		return avgMs[p];
	}

	public static void destroy() {
		if (pool != 0) vkDestroyQueryPool(Helio.device.vkDevice(), pool, null);
		pool = 0;
	}
}
