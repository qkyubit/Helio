package dev.helio.ngx;

import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.config.HelioConfig.DlssQuality;
import dev.helio.vk.Memory;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.lwjgl.vulkan.VK12.*;

/**
 * DLSS on the path tracer's output: Ray Reconstruction (denoise + upscale) for path-traced lighting, Super
 * Resolution (upscale + anti-alias) for hybrid lighting or with RR off. The tracer renders at
 * {@link #renderWidth} x {@link #renderHeight} (from the quality setting) into these images, then
 * {@link #evaluate} records DLSS into the same command buffer, writing {@link #output} at the window's size.
 */
public final class Dlss {
	// NVSDK_NGX_DLSS_Feature_Flags: HDR colour, motion vectors at render size, reversed-Z depth.
	private static final int CREATE_FLAGS = 1 | 2 | 8;
	private static final int DENOISE_MODE_DL_UNIFIED = 1, ROUGHNESS_PACKED = 1, HW_DEPTH = 1;

	/**
	 * Inputs at render size: noisy linear colour, albedos, world normal + roughness, HW depth, motion in pixels,
	 * the reflection ray's hit distance (with the matrices, RR works out how reflections move), and the depth of
	 * the entity or water / glass the primary ray hit (rr_resolve.comp hands it to entity_composite.comp).
	 */
	public static Memory.Img color, diffuse, specular, normals, depth, motion, hitDistance, hitDepth;
	/** Clean linear colour at output size. */
	public static Memory.Img output;
	public static int renderWidth, renderHeight;

	private static final MemorySegment[] features = {MemorySegment.NULL, MemorySegment.NULL}, params = {MemorySegment.NULL, MemorySegment.NULL};
	private static boolean failed, reset = true;
	private static DlssQuality quality;

	private Dlss() {}

	/** Whether the path tracer should render at {@link #renderWidth} and hand its frames to DLSS. */
	public static boolean available() {
		return !failed && Helio.dlss && Helio.ngxCaps != null && !HelioConfig.current.accumulate;
	}

	/** Ray Reconstruction rather than Super Resolution: path-traced lighting, with RR wanted and offered. */
	public static boolean rayReconstruction() {
		HelioConfig c = HelioConfig.current;
		return Helio.ngxCaps.rayReconstruction() && c.rayReconstruction && c.lighting == HelioConfig.Lighting.PATH_TRACED;
	}

	/**
	 * (Re)creates the images for a window size and the current quality. They exist whenever the path tracer
	 * runs (its shader binds them), at the window's size when DLSS isn't in use.
	 */
	public static void ensureImages(VkCommandBuffer cmd, int width, int height, MemoryStack s) throws Throwable {
		DlssQuality q = available() ? HelioConfig.current.dlssQuality : DlssQuality.DLAA;
		int rw = Math.max(1, Math.round(width * q.scale)), rh = Math.max(1, Math.round(height * q.scale));
		if (output != null && output.width() == width && output.height() == height && rw == renderWidth && rh == renderHeight) return;
		if (output != null) {
			Helio.device.graphicsQueue().waitIdle();
			for (Memory.Img img : images()) img.free();
			releaseFeatures();
		}
		quality = q;
		renderWidth = rw;
		renderHeight = rh;
		color = Memory.storageImage(rw, rh, VK_FORMAT_R16G16B16A16_SFLOAT);
		diffuse = Memory.storageImage(rw, rh, VK_FORMAT_R8G8B8A8_UNORM);
		specular = Memory.storageImage(rw, rh, VK_FORMAT_R8G8B8A8_UNORM);
		normals = Memory.storageImage(rw, rh, VK_FORMAT_R16G16B16A16_SFLOAT);
		depth = Memory.storageImage(rw, rh, VK_FORMAT_R32_SFLOAT);
		motion = Memory.storageImage(rw, rh, VK_FORMAT_R16G16_SFLOAT);
		hitDistance = Memory.storageImage(rw, rh, VK_FORMAT_R16_SFLOAT);
		hitDepth = Memory.storageImage(rw, rh, VK_FORMAT_R32_SFLOAT);
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
		return new Memory.Img[]{color, diffuse, specular, normals, depth, motion, hitDistance, hitDepth, output};
	}

	/** Sub-pixel offsets per cycle: DLSS wants 8 per output pixel a render pixel covers. */
	public static int jitterPhases() {
		return (int) Math.ceil(8 / (quality.scale * quality.scale));
	}

	/** DLSS's history no longer matches the picture (DLSS just turned on, a teleport): start over. */
	public static void reset() {
		reset = true;
	}

	/** Records DLSS after the path tracer wrote its inputs (and a barrier). False if it failed: DLSS is then off. */
	public static boolean evaluate(VkCommandBuffer cmd, boolean rr, float jitterX, float jitterY, Matrix4f worldToView, Matrix4f viewToClip) {
		int mode = rr ? 1 : 0;
		try {
			int w = output.width(), h = output.height();
			if (params[mode].equals(MemorySegment.NULL)) params[mode] = Ngx.allocateParameters();
			MemorySegment p = params[mode];
			if (features[mode].equals(MemorySegment.NULL)) {
				Ngx.setDlssCreateParams(p, renderWidth, renderHeight, w, h, quality.perfQuality, CREATE_FLAGS);
				if (rr) {
					Ngx.setInt(p, "DLSS.Denoise.Mode", DENOISE_MODE_DL_UNIFIED);
					Ngx.setUInt(p, "DLSS.Roughness.Mode", ROUGHNESS_PACKED);
					Ngx.setUInt(p, "DLSS.Use.HW.Depth", HW_DEPTH);
				}
				// NGX feature creation puts big buffers on the stack, like init.
				MemorySegment[] out = new MemorySegment[1];
				Helio.onBigStack(() -> out[0] = Ngx.createFeature(Helio.device.vkDevice().address(), cmd.address(),
					rr ? Ngx.FEATURE_RAY_RECONSTRUCTION : Ngx.FEATURE_SUPER_SAMPLING, p));
				features[mode] = out[0];
				reset = true;
				Helio.LOG.info("DLSS {} created: {}x{} -> {}x{} ({})", rr ? "Ray Reconstruction" : "Super Resolution", renderWidth, renderHeight, w, h, quality);
			}
			try (Arena a = Arena.ofConfined()) {
				Ngx.setPointer(p, "Color", resource(a, color, false));
				Ngx.setPointer(p, "Output", resource(a, output, true));
				Ngx.setPointer(p, "Depth", resource(a, depth, false));
				Ngx.setPointer(p, "MotionVectors", resource(a, motion, false));
				if (rr) {
					Ngx.setPointer(p, "DLSS.Input.DiffuseAlbedo", resource(a, diffuse, false));
					Ngx.setPointer(p, "DLSS.Input.SpecularAlbedo", resource(a, specular, false));
					MemorySegment n = resource(a, normals, false);
					Ngx.setPointer(p, "GBuffer.Normals", n);
					Ngx.setPointer(p, "GBuffer.Roughness", n); // packed into the normals' alpha
					Ngx.setPointer(p, "DLSSD.SpecularHitDistance", resource(a, hitDistance, false));
					Ngx.setPointer(p, "WorldToViewMatrix", matrix(a, worldToView));
					Ngx.setPointer(p, "ViewToClipMatrix", matrix(a, viewToClip));
				}
				Ngx.setFloat(p, "Jitter.Offset.X", jitterX);
				Ngx.setFloat(p, "Jitter.Offset.Y", jitterY);
				Ngx.setFloat(p, "MV.Scale.X", 1);
				Ngx.setFloat(p, "MV.Scale.Y", 1);
				Ngx.setInt(p, "Reset", reset ? 1 : 0);
				Ngx.setUInt(p, "DLSS.Render.Subrect.Dimensions.Width", renderWidth);
				Ngx.setUInt(p, "DLSS.Render.Subrect.Dimensions.Height", renderHeight);
				Ngx.setFloat(p, "DLSS.Pre.Exposure", 1);
				Ngx.setFloat(p, "DLSS.Exposure.Scale", 1);
				Ngx.evaluate(cmd.address(), features[mode], p);
			}
			reset = false;
			return true;
		} catch (Throwable t) {
			failed = true;
			Helio.LOG.error("DLSS {} failed; disabled for this session", rr ? "Ray Reconstruction" : "Super Resolution", t);
			return false;
		}
	}

	/** JOML's column-major memory is the row-major, row-vector layout NGX expects. */
	private static MemorySegment matrix(Arena a, Matrix4f m) {
		MemorySegment seg = a.allocate(64, 16);
		MemorySegment.copy(m.get(new float[16]), 0, seg, ValueLayout.JAVA_FLOAT, 0, 16);
		return seg;
	}

	private static MemorySegment resource(Arena a, Memory.Img img, boolean write) {
		return Ngx.imageResource(a, img.view(), img.image(), VK_IMAGE_ASPECT_COLOR_BIT, img.format(), img.width(), img.height(), write);
	}

	private static void releaseFeatures() throws Throwable {
		for (int i = 0; i < features.length; i++) {
			if (features[i].equals(MemorySegment.NULL)) continue;
			Ngx.releaseFeature(features[i]);
			features[i] = MemorySegment.NULL;
		}
	}

	/** Everything NGX holds for DLSS; the GPU is idle and NGX about to shut down. */
	public static void release() throws Throwable {
		releaseFeatures();
		for (int i = 0; i < params.length; i++) {
			if (!params[i].equals(MemorySegment.NULL)) Ngx.destroyParameters(params[i]);
			params[i] = MemorySegment.NULL;
		}
	}

	/** Device closing; the GPU is idle. */
	public static void destroyImages() {
		if (output != null) for (Memory.Img img : images()) img.free();
		output = null;
	}
}
