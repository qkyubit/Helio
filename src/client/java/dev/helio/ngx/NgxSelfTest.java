package dev.helio.ngx;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.helio.Helio;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

/**
 * M0 spike: init NGX on Mojang's device, report what DLSS features the driver offers, and create
 * (then release) a DLSS-SR feature. Runs once at device creation, before the game submits anything,
 * so using the graphics queue here can't race vanilla.
 */
public final class NgxSelfTest {
	private NgxSelfTest() {}

	public static void run(VulkanDevice device, Path helioDir) throws Throwable {
		Ngx.load(helioDir.resolve("natives"));

		String[][] ext = Ngx.requiredExtensions();
		Helio.LOG.info("NGX wants instance exts {} and device exts {}", Arrays.toString(ext[0]), Arrays.toString(ext[1]));

		VkDevice dev = device.vkDevice();
		Ngx.init(dev.getPhysicalDevice().getInstance().address(), dev.getPhysicalDevice().address(), dev.address(),
			VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), dev.getCapabilities().vkGetDeviceProcAddr,
			helioDir.resolve("ngx-data"), helioDir.resolve("ngx"));
		Helio.LOG.info("NGX initialised");

		MemorySegment caps = Ngx.capabilityParameters();
		for (String f : new String[]{"SuperSampling", "SuperSamplingDenoising", "FrameGeneration", "FrameInterpolation"}) {
			Helio.LOG.info("NGX {}: available={} needsUpdatedDriver={} minDriver={}.{} initResult=0x{}", f,
				Ngx.getInt(caps, f + ".Available", -1), Ngx.getInt(caps, f + ".NeedsUpdatedDriver", -1),
				Ngx.getInt(caps, f + ".MinDriverVersionMajor", -1), Ngx.getInt(caps, f + ".MinDriverVersionMinor", -1),
				Integer.toHexString(Ngx.getInt(caps, f + ".FeatureInitResult", 0)));
		}
		Helio.LOG.info("NGX DLSS-G max generated frames per real frame: {}", Ngx.getUInt(caps, "DLSSG.MultiFrameCountMax", -1));
		if (Ngx.getInt(caps, "SuperSampling.Available", 0) != 1) {
			Helio.LOG.warn("DLSS-SR not available, skipping feature creation");
			return;
		}

		// 1280x720 -> 2560x1440 (Performance), low-res motion vectors.
		MemorySegment params = Ngx.allocateParameters();
		Ngx.setDlssCreateParams(params, 1280, 720, 2560, 1440, /* MaxPerf */ 0, /* MVLowRes */ 1 << 1);
		MemorySegment handle = submitOnce(device, cmd -> Ngx.createFeature(dev.address(), cmd, Ngx.FEATURE_SUPER_SAMPLING, params));
		Helio.LOG.info("DLSS-SR feature created: handle=0x{}", Long.toHexString(handle.address()));
		Ngx.releaseFeature(handle);
		Ngx.destroyParameters(params);
	}

	private interface Recorder<T> {
		T record(long cmd) throws Throwable;
	}

	private static <T> T submitOnce(VulkanDevice device, Recorder<T> recorder) throws Throwable {
		VkDevice dev = device.vkDevice();
		var queue = device.graphicsQueue();
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer pPool = s.mallocLong(1);
			vkCheck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(queue.queueFamilyIndex()), null, pPool));
			long pool = pPool.get(0);
			try {
				PointerBuffer pCmd = s.mallocPointer(1);
				vkCheck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default()
					.commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pCmd));
				VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), dev);
				vkCheck(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)));
				T result = recorder.record(cmd.address());
				vkCheck(vkEndCommandBuffer(cmd));
				vkCheck(vkQueueSubmit(queue.vkQueue(), VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(pCmd), VK_NULL_HANDLE));
				vkCheck(vkQueueWaitIdle(queue.vkQueue()));
				return result;
			} finally {
				vkDestroyCommandPool(dev, pool, null);
			}
		}
	}

	private static void vkCheck(int r) {
		if (r != VK_SUCCESS) throw new IllegalStateException("Vulkan error " + r);
	}
}
