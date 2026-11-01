package dev.helio.ngx;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.helio.Helio;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Inits NGX on Mojang's device and reads which DLSS features the driver offers. Runs once at device
 * creation, on {@link Helio#onBigStack}.
 */
public final class NgxStartup {
	private NgxStartup() {}

	/** What the driver offers. {@code mfgMax} is generated frames per real frame (0 = no frame generation). */
	public record Caps(boolean superResolution, boolean rayReconstruction, boolean frameGeneration, int mfgMax) {}

	public static Caps run(VulkanDevice device, Path helioDir) throws Throwable {
		Ngx.load(helioDir.resolve("natives"));

		String[][] ext = Ngx.requiredExtensions();
		Helio.LOG.info("NGX wants instance exts {} and device exts {}", Arrays.toString(ext[0]), Arrays.toString(ext[1]));

		VkDevice dev = device.vkDevice();
		Ngx.init(dev.getPhysicalDevice().getInstance().address(), dev.getPhysicalDevice().address(), dev.address(),
			VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), dev.getCapabilities().vkGetDeviceProcAddr,
			helioDir.resolve("ngx-data"), helioDir.resolve("ngx"));
		Helio.LOG.info("NGX initialised");

		MemorySegment caps = Ngx.capabilityParameters();
		for (String f : new String[]{"SuperSampling", "SuperSamplingDenoising", "FrameGeneration"}) {
			Helio.LOG.info("NGX {}: available={} needsUpdatedDriver={} minDriver={}.{} initResult=0x{}", f,
				Ngx.getInt(caps, f + ".Available", -1), Ngx.getInt(caps, f + ".NeedsUpdatedDriver", -1),
				Ngx.getInt(caps, f + ".MinDriverVersionMajor", -1), Ngx.getInt(caps, f + ".MinDriverVersionMinor", -1),
				Integer.toHexString(Ngx.getInt(caps, f + ".FeatureInitResult", 0)));
		}
		boolean fg = Ngx.getInt(caps, "FrameGeneration.Available", 0) == 1;
		return new Caps(Ngx.getInt(caps, "SuperSampling.Available", 0) == 1,
			Ngx.getInt(caps, "SuperSamplingDenoising.Available", 0) == 1,
			fg, fg ? Math.max(1, Ngx.getUInt(caps, "DLSSG.MultiFrameCountMax", 1)) : 0);
	}
}
