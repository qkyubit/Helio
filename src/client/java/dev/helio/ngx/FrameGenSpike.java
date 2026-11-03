package dev.helio.ngx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.vk.GpuTimers;
import dev.helio.mixin.VulkanCommandEncoderAccessor;
import dev.helio.vk.Shaders;
import dev.helio.vk.Reflex;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * M0 spike: DLSS Frame Generation (2x) presented by Helio itself.
 * <p>
 * Raw NGX DLSS-G (no Streamline) only writes an interpolated image; the app presents it. Per game frame:
 * <ol>
 *   <li>After the level renders: copy depth, compute camera-only motion vectors.</li>
 *   <li>At Mojang's swapchain blit: evaluate DLSS-G (previous real frame -> this one) into {@code interp},
 *       and let Mojang's blit/present show that instead.</li>
 *   <li>Right after: acquire a second swapchain image, blit the real frame into it, present it after Mojang's.</li>
 * </ol>
 * Pacing comes from FIFO (vsync): the two presents land on consecutive refreshes. Uncapped/mailbox
 * would drop the interpolated frame; real pacing (Reflex, present timing) is the next step.
 */
public final class FrameGenSpike {
	private static final int FEATURE_FRAME_GENERATION = 11;
	private static final int VK_FORMAT_RGBA8 = 37, VK_FORMAT_RG16F = 83, VK_FORMAT_D32 = 126;

	private static VulkanDevice device;
	private static boolean failed;
	private static long sampler, setLayout, pipelineLayout, pipeline;

	private static MemorySegment feature = MemorySegment.NULL, params = MemorySegment.NULL;
	private static int width, height;
	private static VulkanGpuTexture depthCopy, motion, interp;
	private static VulkanGpuTextureView depthView, motionView, interpView;

	private static final Matrix4f proj = new Matrix4f(), viewProj = new Matrix4f(), prevViewProj = new Matrix4f();
	private static final Matrix4f lastViewProj = new Matrix4f();
	private static final Vector3f right = new Vector3f(), up = new Vector3f(), fwd = new Vector3f();
	private static Vec3 camera, cameraDelta = Vec3.ZERO, lastCamera;
	private static float far;
	private static boolean captured, generating, reset = true;

	// Second present per frame.
	private static final long[] acquireSems = new long[4];
	private static int acquireIndex;
	private static long[] presentSems = new long[0];
	private static long semsForSwapchain, extraSwapchain;
	private static int extraImage = -1;

	// Stats for the overlay, over 1 s windows.
	private static long statsStart, realFrames, presents;
	public static long renderedFps, presentedFps;

	private FrameGenSpike() {}

	/** After the level frame graph: capture depth + motion while the main depth still holds the world. */
	public static void afterLevel(Vec3 cameraPos, Matrix4f viewRotation, Matrix4f projection, float depthFar) {
		if (failed || !Helio.dlss || HelioConfig.current.frameGeneration < 2 || Helio.device == null || Minecraft.getInstance().level == null) return;
		try {
			if (device == null) init(Helio.device);
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			ensureTargets(main.width, main.height);

			proj.set(projection);
			viewProj.set(projection).mul(viewRotation);
			if (lastCamera == null) {
				lastViewProj.set(viewProj);
				lastCamera = cameraPos;
			}
			prevViewProj.set(lastViewProj);
			cameraDelta = cameraPos.subtract(lastCamera);
			lastViewProj.set(viewProj);
			lastCamera = camera = cameraPos;
			far = depthFar;
			viewRotation.positiveX(right);
			viewRotation.positiveY(up);
			viewRotation.positiveZ(fwd).negate();

			VulkanCommandEncoder encoder = device.createCommandEncoder();
			encoder.copyTextureToTexture(main.getDepthTexture(), depthCopy, 0, 0, 0, 0, 0, width, height);
			VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) encoder).helio$commandBuffer();
			GpuTimers.begin(cmd, GpuTimers.Pass.FG_MOTION);
			dispatchMotion(cmd);
			GpuTimers.end(cmd, GpuTimers.Pass.FG_MOTION);
			captured = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Mojang is about to blit {@code mainView} to its swapchain image: hand it the interpolated frame instead. */
	public static GpuTextureView beforeBlit(VulkanCommandEncoder encoder, GpuTextureView mainView) {
		generating = false;
		if (failed || !captured) {
			reset = true; // menus / no world: history is stale
			Reflex.setPresentsPerFrame(1);
			return mainView;
		}
		captured = false;
		try {
			VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) encoder).helio$commandBuffer();
			if (feature.equals(MemorySegment.NULL)) createFeature(cmd);
			GpuTimers.begin(cmd, GpuTimers.Pass.DLSS_G);
			evaluate(cmd, (VulkanGpuTextureView) mainView);
			GpuTimers.end(cmd, GpuTimers.Pass.DLSS_G);
			try (MemoryStack s = MemoryStack.stackPush()) {
				VulkanCommandEncoder.memoryBarrier(cmd, s);
			}
			reset = false;
			generating = true;
			return interpView;
		} catch (Throwable t) {
			fail(t);
			return mainView;
		}
	}

	/** After Mojang's blit (of the interpolated frame): queue the real frame on a second swapchain image. */
	public static void afterBlit(VulkanCommandEncoder encoder, long swapchain, LongList images, int swapWidth, int swapHeight) {
		if (!generating) return;
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (swapchain != semsForSwapchain) recreatePresentSems(images.size(), swapchain);
			long acquire = acquireSems[acquireIndex = (acquireIndex + 1) % acquireSems.length];
			IntBuffer index = s.mallocInt(1);
			int r = vkAcquireNextImageKHR(device.vkDevice(), swapchain, 5_000_000_000L, acquire, VK_NULL_HANDLE, index);
			if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
				Helio.LOG.warn("FG: second acquire failed ({}), showing interpolated frame only", r);
				return;
			}
			int image = index.get(0);
			VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
			VulkanGpuTexture real = (VulkanGpuTexture) Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
			recordSwapchainBlit(s, cmd, real.vkImage(), images.getLong(image), Math.min(swapWidth, width), Math.min(swapHeight, height));
			check(vkEndCommandBuffer(cmd), "end blit");
			encoder.waitSemaphore(acquire, 0L, 65536L);
			encoder.execute(cmd);
			encoder.signalSemaphore(presentSems[image], 0L, 4096L);
			encoder.signalSemaphore(frameDone, ++frameDoneValue, 65536L); // the pacer times the real present from here
			extraSwapchain = swapchain;
			extraImage = image;
			Reflex.setPresentsPerFrame(2);
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** True when Mojang's present this frame shows the interpolated image and Helio presents the real one after. */
	public static boolean presentsRealFrameItself() {
		return extraImage >= 0;
	}

	/**
	 * Vulkan wants the queue externally synchronised: Mojang's submits, presents and idle waits and the pacer's
	 * present all take this lock (VulkanQueueSubmissionMixin, VulkanGpuSurfaceMixin, VulkanQueueMixin).
	 */
	public static final Object QUEUE_LOCK = new Object();
	private static final ExecutorService pacer = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Helio FG pacer");
		t.setDaemon(true);
		return t;
	});
	private static Future<?> pacedPresent;
	/** Timeline semaphore: value n = frame n's GPU work (DLSS-G and both blits) is done. */
	private static long frameDone, frameDoneValue;
	/** Pacer thread only: when the last frame's GPU work finished, and the smoothed time between them. */
	private static long lastDoneNanos, pacedFrames;
	private static double frameNanos;

	/**
	 * After Mojang presented the interpolated frame: present the real one half a frame after both are ready,
	 * from the pacer thread. Both only become ready when the GPU finishes the frame (the CPU is well ahead when
	 * GPU-bound), so back to back they'd land on consecutive refreshes and motion would still move at the base rate.
	 */
	public static void afterPresent(VkQueue queue) {
		realFrames++;
		presents++;
		long now = System.nanoTime();
		if (extraImage >= 0) {
			long sem = presentSems[extraImage], swapchain = extraSwapchain, id = Reflex.presentId(), value = frameDoneValue;
			int image = extraImage;
			pacedPresent = pacer.submit(() -> presentReal(queue, sem, swapchain, image, id, value));
			presents++;
			extraImage = -1;
		}
		if (statsStart == 0) statsStart = now;
		if (now - statsStart > 1_000_000_000L) {
			double sec = (now - statsStart) / 1e9;
			renderedFps = Math.round(realFrames / sec);
			presentedFps = Math.round(presents / sec);
			statsStart = now;
			realFrames = presents = 0;
		}
	}

	private static void presentReal(VkQueue queue, long semaphore, long swapchain, int image, long id, long frameValue) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			vkWaitSemaphores(device.vkDevice(), VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
				.pSemaphores(s.longs(frameDone)).pValues(s.longs(frameValue)), 1_000_000_000L);
			long done = System.nanoTime();
			if (lastDoneNanos != 0) {
				long dt = Math.min(done - lastDoneNanos, 100_000_000L);
				frameNanos = frameNanos == 0 ? dt : frameNanos * 0.9 + dt * 0.1;
			}
			lastDoneNanos = done;
			long due = done + (long) (frameNanos / 2);
			if (++pacedFrames % 600 == 0) Helio.LOG.info("FG pacing: GPU frame {} ms, real frame {} ms after it", String.format("%.1f", frameNanos / 1e6), String.format("%.1f", frameNanos / 2e6));
			for (long wait; (wait = due - System.nanoTime()) > 0; ) LockSupport.parkNanos(wait);

			VkPresentInfoKHR info = VkPresentInfoKHR.calloc(s).sType$Default()
				.pWaitSemaphores(s.longs(semaphore)).swapchainCount(1).pSwapchains(s.longs(swapchain)).pImageIndices(s.ints(image));
			if (id != 0) info.pNext(VkPresentIdKHR.calloc(s).sType$Default().swapchainCount(1).pPresentIds(s.longs(id)).address());
			int r;
			synchronized (QUEUE_LOCK) {
				r = vkQueuePresentKHR(queue, info);
			}
			if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) Helio.LOG.warn("FG: real-frame present returned {}", r);
		}
	}

	/**
	 * Mojang's present of the interpolated frame, handed to the pacer thread: queued behind last frame's paced
	 * real present, so the order stays generated, real, generated, real without the render thread ever waiting
	 * (waiting stalled the GPU, and the pacer's measured frame time grew with every stall).
	 */
	public static int presentLater(VkQueue queue, VkPresentInfoKHR info) {
		long[] semaphores = new long[info.waitSemaphoreCount()];
		info.pWaitSemaphores().get(0, semaphores);
		long swapchain = info.pSwapchains().get(0);
		int image = info.pImageIndices().get(0);
		pacedPresent = pacer.submit(() -> {
			try (MemoryStack s = MemoryStack.stackPush()) {
				VkPresentInfoKHR copy = VkPresentInfoKHR.calloc(s).sType$Default().pWaitSemaphores(s.longs(semaphores))
					.swapchainCount(1).pSwapchains(s.longs(swapchain)).pImageIndices(s.ints(image));
				int r;
				synchronized (QUEUE_LOCK) {
					r = vkQueuePresentKHR(queue, copy);
				}
				if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) Helio.LOG.warn("FG: interpolated-frame present returned {}", r);
			}
		});
		return VK_SUCCESS;
	}

	/** Before anything waits for the queue to drain (or FG shuts down): the pacer's presents go out first. */
	public static void awaitPacedPresent() {
		Future<?> f = pacedPresent;
		if (f == null) return;
		pacedPresent = null;
		try {
			f.get();
		} catch (Exception e) {
			Helio.LOG.warn("FG: paced present failed", e);
		}
	}

	private static void init(VulkanDevice dev) throws Throwable {
		device = dev;
		VkDevice vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			check(vkCreateSampler(vk, VkSamplerCreateInfo.calloc(s).sType$Default()
				.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).maxLod(0), null, h), "sampler");
			sampler = h.get(0);
			VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, s);
			b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default()
				.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(b), null, h), "set layout");
			setLayout = h.get(0);
			check(vkCreatePipelineLayout(vk, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout))
				.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(144)), null, h), "pipeline layout");
			pipelineLayout = h.get(0);
			ByteBuffer spv = Shaders.compile("camera_motion.comp");
			check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, h), "shader module");
			long module = h.get(0);
			MemoryUtil.memFree(spv);
			VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
			ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
			check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, h), "pipeline");
			pipeline = h.get(0);
			vkDestroyShaderModule(vk, module, null);
			for (int i = 0; i < acquireSems.length; i++) {
				check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default(), null, h), "semaphore");
				acquireSems[i] = h.get(0);
			}
			check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default()
				.pNext(VkSemaphoreTypeCreateInfo.calloc(s).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)), null, h), "timeline semaphore");
			frameDone = h.get(0);
		}
		params = Ngx.allocateParameters();
		Helio.LOG.info("Frame generation spike initialised");
	}

	/** Releases the DLSS-G feature. Call with the GPU idle, before the device goes away. */
	public static void release() throws Throwable {
		awaitPacedPresent();
		if (feature.equals(MemorySegment.NULL)) return;
		Ngx.releaseFeature(feature);
		feature = MemorySegment.NULL;
	}

	private static void ensureTargets(int w, int h) throws Throwable {
		if (w == width && h == height && interp != null) return;
		if (interp != null) {
			device.graphicsQueue().waitIdle();
			for (var v : new VulkanGpuTextureView[]{depthView, motionView, interpView}) v.close();
			for (var t : new VulkanGpuTexture[]{depthCopy, motion, interp}) t.close();
		}
		if (!feature.equals(MemorySegment.NULL)) {
			Ngx.releaseFeature(feature);
			feature = MemorySegment.NULL;
		}
		width = w;
		height = h;
		depthCopy = (VulkanGpuTexture) device.createTexture("Helio FG depth", 15, GpuFormat.D32_FLOAT, w, h, 1, 1);
		motion = (VulkanGpuTexture) device.createTexture("Helio FG motion", 15, GpuFormat.RG16_FLOAT, w, h, 1, 1);
		interp = (VulkanGpuTexture) device.createTexture("Helio FG interpolated", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		depthView = (VulkanGpuTextureView) device.createTextureView(depthCopy, 0, 1);
		motionView = (VulkanGpuTextureView) device.createTextureView(motion, 0, 1);
		interpView = (VulkanGpuTextureView) device.createTextureView(interp, 0, 1);
		reset = true;
	}

	private static void createFeature(VkCommandBuffer cmd) throws Throwable {
		Ngx.setUInt(params, "CreationNodeMask", 1);
		Ngx.setUInt(params, "VisibilityNodeMask", 1);
		Ngx.setUInt(params, "Width", width);
		Ngx.setUInt(params, "Height", height);
		Ngx.setUInt(params, "DLSSG.BackbufferFormat", VK_FORMAT_RGBA8);
		// NGX feature creation puts big buffers on the stack, like init.
		MemorySegment[] out = new MemorySegment[1];
		Helio.onBigStack(() -> out[0] = Ngx.createFeature(device.vkDevice().address(), cmd.address(), FEATURE_FRAME_GENERATION, params));
		feature = out[0];
		Helio.LOG.info("DLSS-G feature created for {}x{}", width, height);
	}

	private static void evaluate(VkCommandBuffer cmd, VulkanGpuTextureView mainView) throws Throwable {
		VulkanGpuTexture main = mainView.texture();
		try (Arena a = Arena.ofConfined()) {
			MemorySegment p = params;
			Ngx.setPointer(p, "DLSSG.Backbuffer", Ngx.imageResource(a, mainView.vkImageView(), main.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RGBA8, width, height, false));
			Ngx.setPointer(p, "DLSSG.MVecs", Ngx.imageResource(a, motionView.vkImageView(), motion.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RG16F, width, height, false));
			Ngx.setPointer(p, "DLSSG.Depth", Ngx.imageResource(a, depthView.vkImageView(), depthCopy.vkImage(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_FORMAT_D32, width, height, false));
			Ngx.setPointer(p, "DLSSG.OutputInterpolated", Ngx.imageResource(a, interpView.vkImageView(), interp.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RGBA8, width, height, true));
			Ngx.setUInt(p, "DLSSG.MultiFrameCount", 1);
			Ngx.setUInt(p, "DLSSG.MultiFrameIndex", 1);

			Matrix4f clipToView = new Matrix4f(proj).invert();
			Matrix4f clipToPrev = new Matrix4f(prevViewProj)
				.translate((float) cameraDelta.x, (float) cameraDelta.y, (float) cameraDelta.z)
				.mul(new Matrix4f(viewProj).invert());
			// JOML's column-major memory equals the row-major/row-vector layout NGX expects.
			Ngx.setPointer(p, "DLSSG.CameraViewToClip", matrix(a, proj));
			Ngx.setPointer(p, "DLSSG.ClipToCameraView", matrix(a, clipToView));
			Ngx.setPointer(p, "DLSSG.ClipToLensClip", matrix(a, new Matrix4f()));
			Ngx.setPointer(p, "DLSSG.ClipToPrevClip", matrix(a, clipToPrev));
			Ngx.setPointer(p, "DLSSG.PrevClipToClip", matrix(a, new Matrix4f(clipToPrev).invert()));
			Ngx.setFloat(p, "DLSSG.JitterOffsetX", 0);
			Ngx.setFloat(p, "DLSSG.JitterOffsetY", 0);
			Ngx.setFloat(p, "DLSSG.MvecScaleX", 1);
			Ngx.setFloat(p, "DLSSG.MvecScaleY", 1);
			Ngx.setFloat(p, "DLSSG.CameraPinholeOffsetX", 0);
			Ngx.setFloat(p, "DLSSG.CameraPinholeOffsetY", 0);
			vec(p, "DLSSG.CameraPos", (float) camera.x, (float) camera.y, (float) camera.z);
			vec(p, "DLSSG.CameraUp", up.x, up.y, up.z);
			vec(p, "DLSSG.CameraRight", right.x, right.y, right.z);
			vec(p, "DLSSG.CameraFwd", fwd.x, fwd.y, fwd.z);
			Ngx.setFloat(p, "DLSSG.CameraNear", 0.05f);
			Ngx.setFloat(p, "DLSSG.CameraFar", far);
			Ngx.setFloat(p, "DLSSG.CameraFOV", proj.perspectiveFov());
			Ngx.setFloat(p, "DLSSG.CameraAspectRatio", (float) width / height);
			Ngx.setUInt(p, "DLSSG.ColorBuffersHDR", 0);
			Ngx.setUInt(p, "DLSSG.DepthInverted", 1);
			Ngx.setUInt(p, "DLSSG.CameraMotionIncluded", 1);
			Ngx.setUInt(p, "DLSSG.Reset", reset ? 1 : 0);
			Ngx.setUInt(p, "DLSSG.NotRenderingGameFrames", 0);
			Ngx.setUInt(p, "DLSSG.OrthoProjection", 0);
			for (String r : new String[]{"MVecs", "Depth", "InputBackbuffer", "OutputInterpolated"}) {
				Ngx.setUInt(p, "DLSSG." + r + "SubrectBaseX", 0);
				Ngx.setUInt(p, "DLSSG." + r + "SubrectBaseY", 0);
				Ngx.setUInt(p, "DLSSG." + r + "SubrectWidth", width);
				Ngx.setUInt(p, "DLSSG." + r + "SubrectHeight", height);
			}
			Ngx.evaluate(cmd.address(), feature, p);
		}
	}

	private static MemorySegment matrix(Arena a, Matrix4f m) {
		MemorySegment seg = a.allocate(64, 16);
		float[] f = m.get(new float[16]);
		MemorySegment.copy(f, 0, seg, ValueLayout.JAVA_FLOAT, 0, 16);
		return seg;
	}

	private static void vec(MemorySegment p, String name, float x, float y, float z) throws Throwable {
		Ngx.setFloat(p, name + "X", x);
		Ngx.setFloat(p, name + "Y", y);
		Ngx.setFloat(p, name + "Z", z);
	}

	private static void dispatchMotion(VkCommandBuffer cmd) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(2, s);
			w.get(0).sType$Default().dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(depthView.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			w.get(1).sType$Default().dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
				.pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(motionView.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, w);
			ByteBuffer pc = s.malloc(144);
			new Matrix4f(viewProj).invert().get(0, pc);
			prevViewProj.get(64, pc);
			pc.putFloat(128, (float) cameraDelta.x).putFloat(132, (float) cameraDelta.y).putFloat(136, (float) cameraDelta.z).putFloat(140, 0);
			vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
			vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
		}
	}

	/** Same as Mojang's swapchain blit (including its Y flip: the main target is stored bottom-up). */
	private static void recordSwapchainBlit(MemoryStack s, VkCommandBuffer cmd, long src, long swapImage, int w, int h) {
		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null,
			VkImageMemoryBarrier.calloc(1, s).sType$Default().dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
				.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(swapImage).subresourceRange(range));
		VkImageBlit.Buffer region = VkImageBlit.calloc(1, s);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.srcOffsets(0).set(0, 0, 0);
		region.srcOffsets(1).set(w, h, 1);
		region.dstOffsets(0).set(0, h, 0);
		region.dstOffsets(1).set(w, 0, 1);
		vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
		vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null,
			VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
				.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
				.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(swapImage).subresourceRange(range));
	}

	private static void recreatePresentSems(int count, long swapchain) {
		VkDevice vk = device.vkDevice();
		device.graphicsQueue().waitIdle();
		for (long sem : presentSems) vkDestroySemaphore(vk, sem, null);
		presentSems = new long[count];
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			for (int i = 0; i < count; i++) {
				check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default(), null, h), "semaphore");
				presentSems[i] = h.get(0);
			}
		}
		semsForSwapchain = swapchain;
	}

	private static void fail(Throwable t) {
		failed = true;
		generating = false;
		extraImage = -1;
		Helio.LOG.error("Frame generation spike failed; disabled for this session", t);
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
