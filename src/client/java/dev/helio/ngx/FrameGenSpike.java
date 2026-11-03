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
import dev.helio.rt.PathTracer;
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
import java.nio.LongBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * DLSS Frame Generation (2x, or multi frame generation 3x/4x) presented by Helio itself.
 * <p>
 * Raw NGX DLSS-G (no Streamline) only writes interpolated images; the app presents them. Per game frame:
 * <ol>
 *   <li>After the level renders: copy depth and the HUD-less colour, compute motion vectors.</li>
 *   <li>At Mojang's swapchain blit: evaluate DLSS-G once per generated frame (previous real frame -> this one)
 *       into {@code interp}, and let Mojang's blit/present show the first instead.</li>
 *   <li>Right after: acquire a swapchain image for each later generated frame and the real one, blit them in,
 *       and present them from the pacer thread, spread evenly over the frame.</li>
 * </ol>
 */
public final class FrameGenSpike {
	private static final int FEATURE_FRAME_GENERATION = 11;
	private static final int VK_FORMAT_RGBA8 = 37, VK_FORMAT_RG16F = 83, VK_FORMAT_D32 = 126;

	private static VulkanDevice device;
	private static boolean failed;
	private static long sampler, setLayout, pipelineLayout, pipeline;

	private static MemorySegment feature = MemorySegment.NULL, params = MemorySegment.NULL;
	private static int width, height;
	private static VulkanGpuTexture depthCopy, motion, hudless;
	private static VulkanGpuTextureView depthView, motionView, hudlessView;
	/** One per generated frame, in display order. */
	private static VulkanGpuTexture[] interp = new VulkanGpuTexture[0];
	private static VulkanGpuTextureView[] interpView = new VulkanGpuTextureView[0];
	/** Generated frames per real frame this session's feature was created for. */
	private static int generated;

	private static final Matrix4f proj = new Matrix4f(), viewProj = new Matrix4f(), prevViewProj = new Matrix4f();
	private static final Matrix4f lastViewProj = new Matrix4f();
	private static final Vector3f right = new Vector3f(), up = new Vector3f(), fwd = new Vector3f();
	private static Vec3 camera, cameraDelta = Vec3.ZERO, lastCamera;
	private static float far;
	private static boolean captured, generating, reset = true;

	// This frame's presents for the pacer besides Mojang's: PacedPresenter staging set and frame count (0: none).
	private static int pendingSet, pendingCount;
	private static long stagedSwapchain;

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
			ensureTargets(main.width, main.height, Math.min(HelioConfig.current.frameGeneration - 1, Helio.ngxCaps.mfgMax()));

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
			// The picture before the hand and GUI go on: DLSS-G keeps what differs from it (the HUD) from the
			// real frame instead of interpolating it.
			encoder.copyTextureToTexture(main.getColorTexture(), hudless, 0, 0, 0, 0, 0, width, height);
			VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) encoder).helio$commandBuffer();
			GpuTimers.begin(cmd, GpuTimers.Pass.FG_MOTION);
			dispatchMotion(cmd);
			PathTracer.entityMotion(cmd, motionView, width, height);
			GpuTimers.end(cmd, GpuTimers.Pass.FG_MOTION);
			captured = true;
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** Whether frame generation presents this frame (as it did the last): Mojang then skips acquiring an image. */
	public static boolean takesOverSwapchain() {
		return generating && !failed;
	}

	/**
	 * In place of Mojang's swapchain blit: generate this frame's frames and stage them with the real one for the
	 * pacer, which acquires and presents each when it's due (PacedPresenter). False: no frame generation this frame
	 * (menus, no world), Mojang blits and presents as usual.
	 */
	public static boolean takeFrame(VulkanCommandEncoder encoder, GpuTextureView mainView, long swapchain, LongList images,
		int swapWidth, int swapHeight, long surface, int format) {
		generating = false;
		if (failed || !captured) {
			reset = true; // menus / no world: history is stale
			Reflex.setPresentsPerFrame(1);
			return false;
		}
		captured = false;
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (!PacedPresenter.available(device, surface)) throw new IllegalStateException("no queue to present generated frames from");
			VkCommandBuffer cmd = ((VulkanCommandEncoderAccessor) encoder).helio$commandBuffer();
			if (feature.equals(MemorySegment.NULL)) createFeature(cmd);
			GpuTimers.begin(cmd, GpuTimers.Pass.DLSS_G);
			for (int i = 0; i < generated; i++) evaluate(cmd, (VulkanGpuTextureView) mainView, i);
			GpuTimers.end(cmd, GpuTimers.Pass.DLSS_G);
			VulkanCommandEncoder.memoryBarrier(cmd, s);
			reset = false;

			long frame = frameDoneValue + 1;
			// The staging set this frame writes was last read by the pacer's copies SETS frames ago: those must be
			// submitted (to wait for them on the GPU below). The pacer is normally well past that.
			awaitPacer(frame - PacedPresenter.SETS);
			int set = (int) (frame % PacedPresenter.SETS), count = generated + 1;
			int w = Math.min(swapWidth, width), h = Math.min(swapHeight, height);
			VkCommandBuffer stage = encoder.allocateAndBeginTransientCommandBuffer();
			if (swapchain != stagedSwapchain || w != PacedPresenter.stagingWidth || h != PacedPresenter.stagingHeight
				|| format != PacedPresenter.stagingFormat || count != stagedCount) {
				awaitPacedPresent();
				PacedPresenter.useSwapchain(swapchain, images.toLongArray());
				PacedPresenter.ensureStaging(stage, count, w, h, format);
				stagedSwapchain = swapchain;
				stagedCount = count;
			}
			VulkanCommandEncoder.memoryBarrier(stage, s);
			long real = ((VulkanGpuTexture) mainView.texture()).vkImage();
			for (int i = 0; i < count; i++) PacedPresenter.recordStage(s, stage, i < generated ? interp[i].vkImage() : real, set, i);
			VulkanCommandEncoder.memoryBarrier(stage, s);
			check(vkEndCommandBuffer(stage), "end staging");
			long reuse = PacedPresenter.setDone.get(set);
			if (reuse > 0) encoder.waitSemaphore(PacedPresenter.copyDoneSemaphore(), reuse, 4096L);
			encoder.execute(stage);
			encoder.signalSemaphore(frameDone, ++frameDoneValue, 65536L); // the pacer times its presents from here
			pendingSet = set;
			pendingCount = count;
			generating = true;
			Reflex.setPresentsPerFrame(count);
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	/**
	 * Start of a frame while frame generation presents (in place of Reflex's sleep): at most one frame queued ahead
	 * of the GPU (the last must be done before the one after it starts), and no faster than the display shows
	 * frames: refresh / presents per frame, 5% under (the pacer keeps presents a refresh apart).
	 */
	public static void limitFrameStart() {
		long due = lastFrameStart + (long) (refreshNanos * (generated + 1) * 1.05);
		for (long wait; (wait = due - System.nanoTime()) > 0 && wait < 100_000_000L; ) LockSupport.parkNanos(wait);
		if (frameDoneValue > 1) try (MemoryStack s = MemoryStack.stackPush()) {
			vkWaitSemaphores(device.vkDevice(), VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
				.pSemaphores(s.longs(frameDone)).pValues(s.longs(frameDoneValue - 1)), 100_000_000L);
		}
		lastFrameStart = System.nanoTime();
	}

	private static long lastFrameStart;

	/** Whether a paced present found the swapchain suboptimal or out of date since the last call. */
	public static boolean swapchainSuboptimal() {
		return PacedPresenter.takeSuboptimal();
	}

	private static int stagedCount;

	private static void awaitPacer(long frame) {
		long start = System.nanoTime(), deadline = start + 1_000_000_000L;
		while (pacedThrough < frame && System.nanoTime() < deadline) LockSupport.parkNanos(50_000);
		pacerWaitNanos += System.nanoTime() - start;
	}

	/** Render thread time spent waiting, for the pacing log: on the pacer, on Mojang's acquire. */
	public static volatile long pacerWaitNanos, acquireWaitNanos;

	/** True when the pacer presents this frame's images (Mojang's present is skipped). */
	public static boolean presentsRealFrameItself() {
		return pendingCount > 0;
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
	/** The last frame (frameDone value) whose presents the pacer has all handed to the queue. */
	private static volatile long pacedThrough;
	private static double frameNanos, worstNanos;
	/** Pacer thread only: when its last present went out. */
	private static long lastPresentNanos, behindFrames;
	/** The display's refresh period; 0 if unknown. Presents keep this far apart, plus a margin for wake-up jitter. */
	private static volatile long refreshNanos;
	private static final long MIN_GAP_MARGIN = 100_000;

	/**
	 * After Mojang presented the first generated frame: present the others and the real one from the pacer
	 * thread, k / (generated + 1) of a frame after the GPU finished. They only become ready then (the CPU is well
	 * ahead when GPU-bound), so back to back they'd land on consecutive refreshes and motion would still move at
	 * the base rate.
	 */
	public static void afterPresent(VkQueue queue) {
		realFrames++;
		presents++;
		long now = System.nanoTime();
		if (pendingCount > 0) {
			int set = pendingSet, count = pendingCount;
			long id = Reflex.presentId(), value = frameDoneValue;
			long gap = refreshNanos;
			pacedPresent = pacer.submit(() -> presentPaced(queue, set, count, id, value, gap));
			presents += count - 1;
			pendingCount = 0;
		}
		if (statsStart == 0) statsStart = now;
		if (now - statsStart > 1_000_000_000L) {
			float hz = Reflex.refreshRate();
			refreshNanos = hz > 0 ? (long) (1e9 / hz) : 0;
			double sec = (now - statsStart) / 1e9;
			renderedFps = Math.round(realFrames / sec);
			presentedFps = Math.round(presents / sec);
			statsStart = now;
			realFrames = presents = 0;
		}
	}

	/**
	 * A frame's presents: Mojang's (the first generated image) as the GPU finishes, then the rest k / n of a frame
	 * later. Never closer than one refresh: without vsync the compositor shows the newest image each refresh
	 * (mailbox), and two presents in one refresh lose the first, a visible hitch at 3x and 4x on a ~180 Hz screen.
	 */
	private static void presentPaced(VkQueue mainQueue, int set, int n, long id, long frameValue, long minGap) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			vkWaitSemaphores(device.vkDevice(), VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
				.pSemaphores(s.longs(frameDone)).pValues(s.longs(frameValue)), 1_000_000_000L);
			long done = System.nanoTime();
			if (lastDoneNanos != 0) {
				long dt = Math.min(done - lastDoneNanos, 100_000_000L);
				frameNanos = frameNanos == 0 ? dt : frameNanos * 0.9 + dt * 0.1;
				worstNanos = Math.max(worstNanos, dt);
			}
			lastDoneNanos = done;
			long copied = 0;
			if (++pacedFrames % 600 == 0) {
				Helio.LOG.info("FG pacing: GPU frame {} ms (worst {} ms), {} presents {} ms apart, refresh {} ms, {} frames behind; render thread waited {} ms/frame on the pacer, {} ms/frame acquiring, {} ms/frame in Reflex's sleep",
					String.format("%.1f", frameNanos / 1e6), String.format("%.1f", worstNanos / 1e6), n, String.format("%.1f", frameNanos / n / 1e6),
					String.format("%.2f", minGap / 1e6), behindFrames, String.format("%.2f", pacerWaitNanos / 600e6), String.format("%.2f", acquireWaitNanos / 600e6),
					String.format("%.2f", Reflex.sleepNanos / 600e6));
				pacerWaitNanos = acquireWaitNanos = Reflex.sleepNanos = 0;
				Helio.LOG.info("FG pacing: GPU ms: path trace {}, DLSS {}, FG motion {}, DLSS-G {}", GpuTimers.ms(GpuTimers.Pass.PATH_TRACE),
					GpuTimers.ms(GpuTimers.Pass.DLSS), GpuTimers.ms(GpuTimers.Pass.FG_MOTION), GpuTimers.ms(GpuTimers.Pass.DLSS_G));
				worstNanos = 0;
				behindFrames = 0;
			}
			// Rendering faster than n presents a refresh apart (no Reflex cap): keeping the gap would fall further
			// behind every frame, so let the compositor drop some instead.
			long gap = lastPresentNanos - done > frameNanos / 2 ? 0 : minGap + MIN_GAP_MARGIN;
			if (gap == 0) behindFrames++;
			// Copy every frame into a swapchain image now, present each when due. Reflex times the frame by when its
			// present's semaphore signals: a copy made at the real frame's due time looked like rendering that much
			// longer, and Reflex slept the game to match (frames took twice the GPU time: 60 -> 40 fps).
			int[] images = new int[n];
			for (int k = 0; k < n; k++) {
				images[k] = PacedPresenter.prepare(set, k);
				if (images[k] >= 0) copied = PacedPresenter.lastCopy;
			}
			for (int k = 0; k < n; k++) {
				long due = Math.max(done + (long) (frameNanos * k / n), lastPresentNanos + gap);
				for (long wait; (wait = due - System.nanoTime()) > 0; ) LockSupport.parkNanos(wait);
				lastPresentNanos = System.nanoTime();
				// The real frame (last) goes out on Mojang's queue with the frame's Reflex present ID: Reflex paces
				// the game by the presents it sees there.
				boolean real = k == n - 1;
				if (images[k] >= 0) PacedPresenter.present(images[k], real ? id : 0, real ? mainQueue : null);
			}
			if (copied > 0) PacedPresenter.setDone.set(set, copied);
			pacedThrough = frameValue;
		}
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

	private static void ensureTargets(int w, int h, int count) throws Throwable {
		if (w == width && h == height && count == generated) return;
		if (depthCopy != null) {
			awaitPacedPresent();
			device.graphicsQueue().waitIdle();
			for (var v : new VulkanGpuTextureView[]{depthView, motionView, hudlessView}) v.close();
			for (var t : new VulkanGpuTexture[]{depthCopy, motion, hudless}) t.close();
			for (var v : interpView) v.close();
			for (var t : interp) t.close();
		}
		if (!feature.equals(MemorySegment.NULL)) {
			Ngx.releaseFeature(feature);
			feature = MemorySegment.NULL;
		}
		width = w;
		height = h;
		depthCopy = (VulkanGpuTexture) device.createTexture("Helio FG depth", 15, GpuFormat.D32_FLOAT, w, h, 1, 1);
		motion = (VulkanGpuTexture) device.createTexture("Helio FG motion", 15, GpuFormat.RG16_FLOAT, w, h, 1, 1);
		hudless = (VulkanGpuTexture) device.createTexture("Helio FG HUD-less", 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		depthView = (VulkanGpuTextureView) device.createTextureView(depthCopy, 0, 1);
		motionView = (VulkanGpuTextureView) device.createTextureView(motion, 0, 1);
		hudlessView = (VulkanGpuTextureView) device.createTextureView(hudless, 0, 1);
		generated = count;
		interp = new VulkanGpuTexture[count];
		interpView = new VulkanGpuTextureView[count];
		for (int i = 0; i < count; i++) {
			interp[i] = (VulkanGpuTexture) device.createTexture("Helio FG generated " + (i + 1), 15, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			interpView[i] = (VulkanGpuTextureView) device.createTextureView(interp[i], 0, 1);
		}
		reset = true;
	}

	private static void createFeature(VkCommandBuffer cmd) throws Throwable {
		Ngx.setUInt(params, "CreationNodeMask", 1);
		Ngx.setUInt(params, "VisibilityNodeMask", 1);
		Ngx.setUInt(params, "Width", width);
		Ngx.setUInt(params, "Height", height);
		Ngx.setUInt(params, "DLSSG.BackbufferFormat", VK_FORMAT_RGBA8);
		Ngx.setUInt(params, "DLSSG.MultiFrameCountMax", generated);
		// NGX feature creation puts big buffers on the stack, like init.
		MemorySegment[] out = new MemorySegment[1];
		Helio.onBigStack(() -> out[0] = Ngx.createFeature(device.vkDevice().address(), cmd.address(), FEATURE_FRAME_GENERATION, params));
		feature = out[0];
		Helio.LOG.info("DLSS-G feature created for {}x{}, {} generated frames per real frame", width, height, generated);
	}

	/** Generated frame {@code i} (0-based, in display order) into {@code interp[i]}. */
	private static void evaluate(VkCommandBuffer cmd, VulkanGpuTextureView mainView, int i) throws Throwable {
		VulkanGpuTexture main = mainView.texture();
		try (Arena a = Arena.ofConfined()) {
			MemorySegment p = params;
			Ngx.setPointer(p, "DLSSG.Backbuffer", Ngx.imageResource(a, mainView.vkImageView(), main.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RGBA8, width, height, false));
			Ngx.setPointer(p, "DLSSG.MVecs", Ngx.imageResource(a, motionView.vkImageView(), motion.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RG16F, width, height, false));
			Ngx.setPointer(p, "DLSSG.Depth", Ngx.imageResource(a, depthView.vkImageView(), depthCopy.vkImage(), VK_IMAGE_ASPECT_DEPTH_BIT, VK_FORMAT_D32, width, height, false));
			Ngx.setPointer(p, "DLSSG.HUDLess", Ngx.imageResource(a, hudlessView.vkImageView(), hudless.vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RGBA8, width, height, false));
			Ngx.setPointer(p, "DLSSG.OutputInterpolated", Ngx.imageResource(a, interpView[i].vkImageView(), interp[i].vkImage(), VK_IMAGE_ASPECT_COLOR_BIT, VK_FORMAT_RGBA8, width, height, true));
			Ngx.setUInt(p, "DLSSG.MultiFrameCount", generated);
			Ngx.setUInt(p, "DLSSG.MultiFrameIndex", i + 1);

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
			for (String r : new String[]{"MVecs", "Depth", "InputBackbuffer", "OutputInterpolated", "HUDLess"}) {
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

	private static void fail(Throwable t) {
		failed = true;
		generating = false;
		pendingCount = 0;
		Helio.LOG.error("Frame generation spike failed; disabled for this session", t);
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
