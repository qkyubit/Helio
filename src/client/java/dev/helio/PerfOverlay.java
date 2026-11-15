package dev.helio;

import dev.helio.config.HelioConfig;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.ngx.Dlss;
import dev.helio.rt.Lights;
import dev.helio.rt.PathTracer;
import dev.helio.rt.Scene;
import dev.helio.vk.GpuTimers;
import dev.helio.vk.GpuTimers.Pass;
import dev.helio.vk.Memory;
import dev.helio.vk.Reflex;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.vma.Vma.vmaGetHeapBudgets;
import static org.lwjgl.vulkan.VK11.VK_MAX_MEMORY_HEAPS;
import static org.lwjgl.vulkan.VK11.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2;

/** Top-right HUD: GPU time per Helio pass, frame generation, Reflex latency, VRAM. Toggled in settings. */
public final class PerfOverlay {
	private static long lastSlowUpdate;
	private static double latencyMs = Double.NaN;
	/**
	 * Device-local heaps' usage and budget (VK_EXT_memory_budget), refreshed by {@link #readVram}; vramSlack: what
	 * Helio's allocator holds but has free inside its blocks (VMA keeps blocks after their buffers are freed, so
	 * usage doesn't fall when the scene shrinks; new buffers reuse that space first).
	 */
	public static long vramUsed, vramBudget, vramSlack;

	private PerfOverlay() {}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!HelioConfig.current.perfOverlay || mc.debugEntries.isOverlayVisible()) return;
		long now = System.nanoTime();
		if (now - lastSlowUpdate > 500_000_000L) {
			lastSlowUpdate = now;
			latencyMs = Reflex.medianLatencyMs();
			readVram();
		}

		HelioConfig c = HelioConfig.current;
		List<String> lines = new ArrayList<>();
		lines.add("Helio " + c.preset.name().toLowerCase() + ", " + mc.getFps() + " fps");
		if (!Helio.rayTracing) lines.add("Ray tracing: unsupported");
		else if (!c.rayTracing) lines.add("Ray tracing: off");
		else {
			lines.add("Scene " + Scene.instanceCount + " BLAS, " + Scene.tlasTriangles / 1000 + "k tris + " + Scene.dynamicTriangles / 1000 + "k entity, " + Scene.sectionCount + " sections, "
				+ Scene.pendingCount + " queued, build " + ms(Pass.SCENE_BUILD) + ", " + (Memory.allocated.get() >> 20) + " MB"
				+ (Scene.vramDistance < Integer.MAX_VALUE ? ", RT distance " + Scene.vramDistance + " (memory guard)" : ""));
			lines.add((c.lighting == HelioConfig.Lighting.HYBRID ? "Hybrid tracing " : "Path tracing ") + ms(Pass.PATH_TRACE) + ", " + Lights.count + " lights"
				+ (c.accumulate ? ", " + PathTracer.frameIndex + " frames accumulated"
					: Dlss.available() ? ", DLSS " + (Dlss.rayReconstruction() ? "RR " : "SR ") + Dlss.renderWidth + "x" + Dlss.renderHeight + " " + ms(Pass.DLSS) : ""));
		}
		if (!Double.isNaN(GpuTimers.ms(Pass.DLSS_G)))
			lines.add("FG " + FrameGenSpike.renderedFps + " -> " + FrameGenSpike.presentedFps + " fps, DLSS-G " + ms(Pass.DLSS_G) + ", motion " + ms(Pass.FG_MOTION));
		else lines.add("Frame generation: " + (Helio.dlss ? "off" : "DLSS " + Helio.dlssStatus));
		if (Helio.reflex) lines.add("Reflex " + c.reflex.name().toLowerCase() + (Double.isNaN(latencyMs) ? "" : String.format(", input -> GPU %.1f ms", latencyMs)));
		if (vramBudget > 0) lines.add(String.format("VRAM %.1f / %.1f GB", vramUsed / 1e9, vramBudget / 1e9));

		int y = 2;
		for (String line : lines) {
			int w = mc.font.width(line);
			int x = g.guiWidth() - w - 2;
			g.fill(x - 1, y - 1, x + w + 1, y + 9, 0x90505050);
			g.text(mc.font, line, x, y, 0xFFE0E0E0, false);
			y += 10;
		}
	}

	private static String ms(Pass pass) {
		double v = GpuTimers.ms(pass);
		return Double.isNaN(v) ? "-" : String.format("%.2f ms", v);
	}

	/** VK_EXT_memory_budget numbers for device-local heaps (zero if the driver doesn't report them). */
	public static void readVram() {
		if (Helio.device == null) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryBudgetPropertiesEXT budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(s).sType$Default();
			VkPhysicalDeviceMemoryProperties2 props = VkPhysicalDeviceMemoryProperties2.calloc(s).sType$Default().pNext(budget.address());
			vkGetPhysicalDeviceMemoryProperties2(Helio.device.vkDevice().getPhysicalDevice(), props);
			VkPhysicalDeviceMemoryProperties mem = props.memoryProperties();
			VmaBudget.Buffer vma = VmaBudget.calloc(VK_MAX_MEMORY_HEAPS, s);
			if (Memory.vma != 0) vmaGetHeapBudgets(Memory.vma, vma);
			long used = 0, total = 0, slack = 0;
			for (int i = 0; i < mem.memoryHeapCount(); i++) {
				if ((mem.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) continue;
				used += budget.heapUsage(i);
				total += budget.heapBudget(i);
				slack += vma.get(i).statistics().blockBytes() - vma.get(i).statistics().allocationBytes();
			}
			vramUsed = used;
			vramBudget = total;
			vramSlack = slack;
		}
	}
}
