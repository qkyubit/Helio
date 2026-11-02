package dev.helio.ngx;

import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.vk.Memory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.lwjgl.vulkan.VK12.*;

/**
 * DLSS Ray Reconstruction: turns the path tracer's noisy frame into a clean one using its guide buffers.
 * The path tracer writes the inputs into these images, then {@link #evaluate} records RR into the same
 * command buffer. Native resolution (DLAA) for now; upscaling is M4 step 2.
 */
public final class RayReconstruction {
	private static final int PERF_QUALITY_DLAA = 5;
	// NVSDK_NGX_DLSS_Feature_Flags: HDR colour, motion vectors at render size, reversed-Z depth.
	private static final int CREATE_FLAGS = 1 | 2 | 8;
	private static final int DENOISE_MODE_DL_UNIFIED = 1, ROUGHNESS_PACKED = 1, HW_DEPTH = 1;

	/** Inputs at render size: noisy linear colour, albedos, world normal + roughness, HW depth, motion in pixels. */
	public static Memory.Img color, diffuse, specular, normals, depth, motion;
	/** Clean linear colour at output size. */
	public static Memory.Img output;

	private static MemorySegment feature = MemorySegment.NULL, params = MemorySegment.NULL;
	private static boolean failed, reset = true;

	private RayReconstruction() {}

	/** Whether the path tracer should hand its frames to RR. */
	public static boolean active() {
		return !failed && Helio.dlss && Helio.ngxCaps != null && Helio.ngxCaps.rayReconstruction()
			&& HelioConfig.current.rayReconstruction && !HelioConfig.current.accumulate;
	}

	/** (Re)creates the images for a new size. They exist whenever the path tracer runs: its shader binds them. */
	public static void ensureImages(VkCommandBuffer cmd, int width, int height, MemoryStack s) throws Throwable {
		if (output != null && output.width() == width && output.height() == height) return;
		if (output != null) {
			Helio.device.graphicsQueue().waitIdle();
			for (Memory.Img img : images()) img.free();
			releaseFeature();
		}
		color = Memory.storageImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT);
		diffuse = Memory.storageImage(width, height, VK_FORMAT_R8G8B8A8_UNORM);
		specular = Memory.storageImage(width, height, VK_FORMAT_R8G8B8A8_UNORM);
		normals = Memory.storageImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT);
		depth = Memory.storageImage(width, height, VK_FORMAT_R32_SFLOAT);
		motion = Memory.storageImage(width, height, VK_FORMAT_R16G16_SFLOAT);
		output = Memory.storageImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT);
		Memory.Img[] all = images();
		VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(all.length, s);
		for (int i = 0; i < all.length; i++) {
			barrier.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
				.image(all[i].image()).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
				.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		}
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
	}

	private static Memory.Img[] images() {
		return new Memory.Img[]{color, diffuse, specular, normals, depth, motion, output};
	}

	/** RR's history no longer matches the picture (RR just turned on, a teleport): start over. */
	public static void reset() {
		reset = true;
	}

	/** Records RR after the path tracer wrote its inputs (and a barrier). False if RR failed: show the noisy frame. */
	public static boolean evaluate(VkCommandBuffer cmd, float jitterX, float jitterY) {
		try {
			int w = output.width(), h = output.height();
			if (params.equals(MemorySegment.NULL)) params = Ngx.allocateParameters();
			if (feature.equals(MemorySegment.NULL)) {
				Ngx.setDlssCreateParams(params, w, h, w, h, PERF_QUALITY_DLAA, CREATE_FLAGS);
				Ngx.setInt(params, "DLSS.Denoise.Mode", DENOISE_MODE_DL_UNIFIED);
				Ngx.setUInt(params, "DLSS.Roughness.Mode", ROUGHNESS_PACKED);
				Ngx.setUInt(params, "DLSS.Use.HW.Depth", HW_DEPTH);
				// NGX feature creation puts big buffers on the stack, like init.
				MemorySegment[] out = new MemorySegment[1];
				Helio.onBigStack(() -> out[0] = Ngx.createFeature(Helio.device.vkDevice().address(), cmd.address(), Ngx.FEATURE_RAY_RECONSTRUCTION, params));
				feature = out[0];
				reset = true;
				Helio.LOG.info("DLSS Ray Reconstruction created for {}x{}", w, h);
			}
			try (Arena a = Arena.ofConfined()) {
				MemorySegment p = params;
				Ngx.setPointer(p, "Color", resource(a, color, false));
				Ngx.setPointer(p, "Output", resource(a, output, true));
				Ngx.setPointer(p, "Depth", resource(a, depth, false));
				Ngx.setPointer(p, "MotionVectors", resource(a, motion, false));
				Ngx.setPointer(p, "DLSS.Input.DiffuseAlbedo", resource(a, diffuse, false));
				Ngx.setPointer(p, "DLSS.Input.SpecularAlbedo", resource(a, specular, false));
				MemorySegment n = resource(a, normals, false);
				Ngx.setPointer(p, "GBuffer.Normals", n);
				Ngx.setPointer(p, "GBuffer.Roughness", n); // packed into the normals' alpha
				Ngx.setFloat(p, "Jitter.Offset.X", jitterX);
				Ngx.setFloat(p, "Jitter.Offset.Y", jitterY);
				Ngx.setFloat(p, "MV.Scale.X", 1);
				Ngx.setFloat(p, "MV.Scale.Y", 1);
				Ngx.setInt(p, "Reset", reset ? 1 : 0);
				Ngx.setUInt(p, "DLSS.Render.Subrect.Dimensions.Width", w);
				Ngx.setUInt(p, "DLSS.Render.Subrect.Dimensions.Height", h);
				Ngx.setFloat(p, "DLSS.Pre.Exposure", 1);
				Ngx.setFloat(p, "DLSS.Exposure.Scale", 1);
				Ngx.evaluate(cmd.address(), feature, p);
			}
			reset = false;
			return true;
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("DLSS Ray Reconstruction failed; disabled for this session", t);
			return false;
		}
	}

	private static MemorySegment resource(Arena a, Memory.Img img, boolean write) {
		return Ngx.imageResource(a, img.view(), img.image(), VK_IMAGE_ASPECT_COLOR_BIT, img.format(), img.width(), img.height(), write);
	}

	private static void releaseFeature() throws Throwable {
		if (feature.equals(MemorySegment.NULL)) return;
		Ngx.releaseFeature(feature);
		feature = MemorySegment.NULL;
	}

	/** Everything NGX holds for RR; the GPU is idle and NGX about to shut down. */
	public static void release() throws Throwable {
		releaseFeature();
		if (!params.equals(MemorySegment.NULL)) Ngx.destroyParameters(params);
		params = MemorySegment.NULL;
	}

	/** Device closing; the GPU is idle. */
	public static void destroyImages() {
		if (output != null) for (Memory.Img img : images()) img.free();
		output = null;
	}
}
