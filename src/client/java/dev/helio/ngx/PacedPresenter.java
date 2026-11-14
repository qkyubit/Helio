package dev.helio.ngx;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.helio.Helio;
import dev.helio.vk.Memory;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Frame generation's presents: every frame (generated ones and the real one) acquired, copied and presented just
 * in time from the pacer thread, on a queue of its own (Mojang's async compute queue if it can present, else an
 * extra graphics-family queue, VulkanBackendMixin). Mojang leaves the swapchain alone meanwhile.
 * <ul>
 *   <li>Acquiring up front on the render thread kept 1 + generated images per frame waiting on the pacer: more
 *       than the swapchain's 8 at 4x, and the GPU then waited for the image on screen (a deadlock).</li>
 *   <li>On Mojang's queue, a blit waits for its image to come off the screen and holds up the next frame's
 *       rendering behind it (60 -> 40 fps); copies would also queue behind that rendering instead of going out on
 *       time.</li>
 * </ul>
 * Per frame, the render thread blits the frames into a set of staging images in the swapchain's format and
 * orientation (on Mojang's queue, after DLSS-G); the pacer copies one into a freshly acquired swapchain image
 * when it's due. {@value #SETS} sets in turn: a set is reused only after the copies out of it ran.
 */
public final class PacedPresenter {
	static final int SETS = 3;
	/** Index of the extra graphics-family queue VulkanBackendMixin asked for, or -1. */
	public static int extraGraphicsQueue = -1;
	private static final int SLOTS = 16;

	private static VulkanDevice device;
	private static VkQueue queue;
	private static boolean ready, unsupported;
	private static int family;
	private static long pool, copyDone, copyValue;
	private static final VkCommandBuffer[] cmds = new VkCommandBuffer[SLOTS];
	private static final long[] acquireSems = new long[SLOTS], slotValues = new long[SLOTS];
	private static int slot;
	/** copyDone value once the copies out of each set have run (render thread waits on it before reusing the set). */
	static final AtomicLongArray setDone = new AtomicLongArray(SETS);

	private static long[][] staging = new long[SETS][0], stagingAlloc = new long[SETS][0];
	static int stagingWidth, stagingHeight, stagingFormat;

	private static long swapchain;
	private static volatile boolean suboptimal;
	private static long[] swapImages = new long[0], presentSems = new long[0];

	private PacedPresenter() {}

	/**
	 * Whether there's a second queue that can present to the surface (the async compute one, else an extra one in
	 * the graphics family); sets it up the first time.
	 */
	static boolean available(VulkanDevice dev, long surface) {
		if (ready || unsupported) return ready;
		VkDevice vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			VulkanQueue compute = dev.computeQueue();
			IntBuffer supported = s.mallocInt(1);
			vkGetPhysicalDeviceSurfaceSupportKHR(vk.getPhysicalDevice(), compute.queueFamilyIndex(), surface, supported);
			if (compute != dev.graphicsQueue() && supported.get(0) != 0) {
				family = compute.queueFamilyIndex();
				queue = compute.vkQueue();
			} else if (extraGraphicsQueue >= 0) {
				family = dev.graphicsQueue().queueFamilyIndex();
				PointerBuffer q = s.mallocPointer(1);
				vkGetDeviceQueue(vk, family, extraGraphicsQueue, q);
				queue = new VkQueue(q.get(0), vk);
			} else {
				unsupported = true;
				Helio.LOG.warn("FG: no second queue that can present; frame generation is off");
				return false;
			}
			// Reflex: this queue only presents frames generated from the render loop's; leave it out of the frame's
			// timing (else Reflex counts its copies and presents as rendering and sleeps the game down).
			if (Helio.reflex) NVLowLatency2.vkQueueNotifyOutOfBandNV(queue,
				VkOutOfBandQueueTypeInfoNV.calloc(s).sType$Default().queueType(NVLowLatency2.VK_OUT_OF_BAND_QUEUE_TYPE_PRESENT_NV));
			device = dev;
			LongBuffer h = s.mallocLong(1);
			check(vkCreateCommandPool(vk, VkCommandPoolCreateInfo.calloc(s).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, h), "command pool");
			pool = h.get(0);
			PointerBuffer p = s.mallocPointer(SLOTS);
			check(vkAllocateCommandBuffers(vk, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool)
				.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(SLOTS), p), "command buffers");
			for (int i = 0; i < SLOTS; i++) {
				cmds[i] = new VkCommandBuffer(p.get(i), vk);
				check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default(), null, h), "semaphore");
				acquireSems[i] = h.get(0);
			}
			check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default()
				.pNext(VkSemaphoreTypeCreateInfo.calloc(s).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)), null, h), "timeline semaphore");
			copyDone = h.get(0);
			ready = true;
			Helio.LOG.info("FG: presenting generated frames from queue family {}", family);
			return true;
		}
	}

	static long copyDoneSemaphore() {
		return copyDone;
	}

	/**
	 * Staging images for {@code count} frames at this size and format, in VK_IMAGE_LAYOUT_GENERAL (barrier recorded
	 * into {@code cmd}). Render thread; the pacer must be idle when they change. Returns true if (re)created.
	 */
	static boolean ensureStaging(VkCommandBuffer cmd, int count, int w, int h, int format) {
		if (staging[0].length == count && w == stagingWidth && h == stagingHeight && format == stagingFormat) return false;
		vkQueueWaitIdle(queue);
		freeStaging();
		stagingWidth = w;
		stagingHeight = h;
		stagingFormat = format;
		try (MemoryStack s = MemoryStack.stackPush()) {
			int graphics = device.graphicsQueue().queueFamilyIndex();
			int[] families = family == graphics ? new int[]{graphics} : new int[]{graphics, family};
			VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(SETS * count, s);
			for (int set = 0; set < SETS; set++) {
				staging[set] = new long[count];
				stagingAlloc[set] = new long[count];
				for (int i = 0; i < count; i++) {
					LongBuffer pImage = s.mallocLong(1);
					PointerBuffer pAlloc = s.mallocPointer(1);
					VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
						.mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
						.usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
						// Written on Mojang's queue, read on the present queue: no ownership transfers.
						.sharingMode(families.length > 1 ? VK_SHARING_MODE_CONCURRENT : VK_SHARING_MODE_EXCLUSIVE).pQueueFamilyIndices(s.ints(families))
						.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
					ci.extent().set(w, h, 1);
					check(vmaCreateImage(Memory.vma, ci, VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO), pImage, pAlloc, null), "vmaCreateImage");
					staging[set][i] = pImage.get(0);
					stagingAlloc[set][i] = pAlloc.get(0);
					barriers.get(set * count + i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
						.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(pImage.get(0))
						.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
				}
				setDone.set(set, 0);
			}
			if (count > 0) vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barriers);
		}
		return true;
	}

	/** Records the blit of one frame into staging image {@code i} of {@code set} (Y flipped, as Mojang's swapchain blit). */
	static void recordStage(MemoryStack s, VkCommandBuffer cmd, long src, int set, int i) {
		VkImageBlit.Buffer region = VkImageBlit.calloc(1, s);
		region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
		region.srcOffsets(0).set(0, 0, 0);
		region.srcOffsets(1).set(stagingWidth, stagingHeight, 1);
		region.dstOffsets(0).set(0, stagingHeight, 0);
		region.dstOffsets(1).set(stagingWidth, 0, 1);
		vkCmdBlitImage(cmd, src, VK_IMAGE_LAYOUT_GENERAL, staging[set][i], VK_IMAGE_LAYOUT_GENERAL, region, VK_FILTER_NEAREST);
	}

	/** The swapchain the pacer presents to. Render thread, with the pacer idle. */
	static void useSwapchain(long chain, long[] images) {
		if (chain == swapchain && images.length == swapImages.length) return;
		vkQueueWaitIdle(queue);
		VkDevice vk = device.vkDevice();
		for (long sem : presentSems) vkDestroySemaphore(vk, sem, null);
		presentSems = new long[images.length];
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer h = s.mallocLong(1);
			for (int i = 0; i < images.length; i++) {
				check(vkCreateSemaphore(vk, VkSemaphoreCreateInfo.calloc(s).sType$Default(), null, h), "semaphore");
				presentSems[i] = h.get(0);
			}
		}
		swapchain = chain;
		swapImages = images;
	}

	/** copyDone value of the last {@link #prepare}'s copy. */
	static long lastCopy;

	/**
	 * Pacer thread: acquires a swapchain image and copies staging image {@code i} of {@code set} into it, ready for
	 * {@link #present}. The copy runs once {@code frameSemaphore} (a timeline) reaches {@code frameValue}: the frame
	 * is staged. Returns the image index, or -1 if none could be acquired.
	 */
	static int prepare(int set, int i, long frameSemaphore, long frameValue) {
		int k = slot = (slot + 1) % SLOTS;
		VkDevice vk = device.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (slotValues[k] > 0) vkWaitSemaphores(vk, VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
				.pSemaphores(s.longs(copyDone)).pValues(s.longs(slotValues[k])), 1_000_000_000L);
			int image = acquire(acquireSems[k], s);
			// ponytail: frames skipped while the swapchain is out of date; Minecraft recreates it on the next frame.
			if (image < 0) return -1;

			VkCommandBuffer cmd = cmds[k];
			vkResetCommandBuffer(cmd, 0);
			vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(s).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null,
				VkImageMemoryBarrier.calloc(1, s).sType$Default().dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
					.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(swapImages[image]).subresourceRange(range));
			VkImageCopy.Buffer region = VkImageCopy.calloc(1, s);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(stagingWidth, stagingHeight, 1);
			vkCmdCopyImage(cmd, staging[set][i], VK_IMAGE_LAYOUT_GENERAL, swapImages[image], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null,
				VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
					.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
					.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(swapImages[image]).subresourceRange(range));
			vkEndCommandBuffer(cmd);

			long value = ++copyValue;
			VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, s).sType$Default()
				.waitSemaphoreCount(2).pWaitSemaphores(s.longs(acquireSems[k], frameSemaphore))
				.pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
				.pCommandBuffers(s.pointers(cmd)).pSignalSemaphores(s.longs(presentSems[image], copyDone))
				.pNext(VkTimelineSemaphoreSubmitInfo.calloc(s).sType$Default().waitSemaphoreValueCount(2).pWaitSemaphoreValues(s.longs(0, frameValue))
					.signalSemaphoreValueCount(2).pSignalSemaphoreValues(s.longs(0, value)));
			check(vkQueueSubmit(queue, submit, VK_NULL_HANDLE), "present copy submit");
			slotValues[k] = value;
			lastCopy = value;
			return image;
		}
	}

	/** Pacer thread: waits until the copy with this copyDone value has run. */
	static void awaitCopy(long value) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			vkWaitSemaphores(device.vkDevice(), VkSemaphoreWaitInfo.calloc(s).sType$Default().semaphoreCount(1)
				.pSemaphores(s.longs(copyDone)).pValues(s.longs(value)), 1_000_000_000L);
		}
	}

	/** Pacer thread: presents a {@link #prepare}d image (on {@code presentQueue}, else this class's queue), with a Reflex present ID if nonzero. */
	static void present(int image, long presentId, VkQueue presentQueue) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			VkPresentInfoKHR info = VkPresentInfoKHR.calloc(s).sType$Default().pWaitSemaphores(s.longs(presentSems[image]))
				.swapchainCount(1).pSwapchains(s.longs(swapchain)).pImageIndices(s.ints(image));
			if (presentId != 0) info.pNext(VkPresentIdKHR.calloc(s).sType$Default().swapchainCount(1).pPresentIds(s.longs(presentId)).address());
			int r;
			synchronized (FrameGenSpike.QUEUE_LOCK) {
				r = vkQueuePresentKHR(presentQueue != null ? presentQueue : queue, info);
			}
			if (r == VK_SUBOPTIMAL_KHR || r == VK_ERROR_OUT_OF_DATE_KHR) suboptimal = true;
			else if (r != VK_SUCCESS) Helio.LOG.warn("FG: paced present returned {}", r);
		}
	}

	/** A swapchain image, polled (the render thread acquires and presents on the same swapchain), or -1. */
	private static int acquire(long semaphore, MemoryStack s) {
		IntBuffer index = s.mallocInt(1);
		long deadline = System.nanoTime() + 500_000_000L;
		while (true) {
			int r;
			synchronized (FrameGenSpike.QUEUE_LOCK) {
				r = vkAcquireNextImageKHR(device.vkDevice(), swapchain, 0, semaphore, VK_NULL_HANDLE, index);
			}
			if (r == VK_SUBOPTIMAL_KHR) suboptimal = true;
			if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) return index.get(0);
			if (r == VK_ERROR_OUT_OF_DATE_KHR) {
				suboptimal = true;
				return -1;
			}
			if ((r != VK_NOT_READY && r != VK_TIMEOUT) || System.nanoTime() > deadline) {
				Helio.LOG.warn("FG: acquire for a paced present failed ({})", r);
				return -1;
			}
			LockSupport.parkNanos(100_000);
		}
	}

	/** Waits for the present queue's copies and presents to finish (no-op before it's set up). */
	public static void waitIdle() {
		if (ready) vkQueueWaitIdle(queue);
	}

	/** Whether a present or acquire found the swapchain suboptimal or out of date since the last call. */
	static boolean takeSuboptimal() {
		boolean was = suboptimal;
		suboptimal = false;
		return was;
	}

	private static void freeStaging() {
		for (int set = 0; set < SETS; set++) {
			for (int i = 0; i < staging[set].length; i++) vmaDestroyImage(Memory.vma, staging[set][i], stagingAlloc[set][i]);
			staging[set] = new long[0];
			stagingAlloc[set] = new long[0];
		}
	}

	private static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
