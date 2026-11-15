package dev.helio.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Helio's own VMA allocator: Mojang's lacks the buffer-device-address flag that acceleration
 * structures and buffer references need. VMA is internally synchronised, so section-compile worker
 * threads allocate here too.
 */
public final class Memory {
	public static long vma;
	private static VkDevice vk;
	/** Bytes currently allocated through {@link #buffer}, for the overlay. */
	public static final AtomicLong allocated = new AtomicLong();

	/**
	 * Buffers this big get memory of their own, back to the driver as soon as they're freed. In shared blocks, the
	 * scene's evicted sections left blocks part-used: VMA kept them, and vanilla, needing VRAM, spilled into system
	 * memory instead (M7 step 6).
	 */
	private static final long DEDICATED_BYTES = 256 << 10;

	/** address and mapped are rounded up to 256 bytes; offset is how far that is into the VkBuffer. */
	public record Buf(long buffer, long allocation, long address, long mapped, long size, long offset) {
		public void free() {
			vmaDestroyBuffer(vma, buffer, allocation);
			allocated.addAndGet(-size);
		}
	}

	private Memory() {}

	public static void init(VulkanDevice dev) {
		vk = dev.vkDevice();
		try (MemoryStack s = MemoryStack.stackPush()) {
			PointerBuffer pVma = s.mallocPointer(1);
			check(vmaCreateAllocator(VmaAllocatorCreateInfo.calloc(s)
				.flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
				// 16 MB blocks (default 256): VMA hands a block back to the driver only once it's empty, and with big
				// blocks evicted sections left them part-used, so the scene shrinking freed no VRAM (M7 step 6).
				.preferredLargeHeapBlockSize(16L << 20)
				.physicalDevice(vk.getPhysicalDevice()).device(vk).instance(vk.getPhysicalDevice().getInstance())
				.vulkanApiVersion(VK_API_VERSION_1_2)
				.pVulkanFunctions(VmaVulkanFunctions.calloc(s).set(vk.getPhysicalDevice().getInstance(), vk)), pVma), "vmaCreateAllocator");
			vma = pVma.get(0);
		}
	}

	public static void destroy() {
		if (vma != 0) vmaDestroyAllocator(vma);
		vma = 0;
	}

	/**
	 * Buffer with a 256-byte-aligned device address (what acceleration structures, micromaps and scratch need;
	 * VMA can't be asked for it in this LWJGL); host-visible ones are persistently mapped.
	 */
	public static Buf buffer(long size, int usage, boolean hostVisible) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer pBuf = s.mallocLong(1);
			PointerBuffer pAlloc = s.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(s);
			check(vmaCreateBuffer(vma,
				VkBufferCreateInfo.calloc(s).sType$Default().size(size + 255).usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT),
				VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO)
					.flags((hostVisible ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0)
						| (size >= DEDICATED_BYTES ? VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT : 0)),
				pBuf, pAlloc, info), "vmaCreateBuffer");
			long raw = vkGetBufferDeviceAddress(vk, VkBufferDeviceAddressInfo.calloc(s).sType$Default().buffer(pBuf.get(0)));
			long offset = (256 - (raw & 255)) & 255;
			allocated.addAndGet(size);
			long mapped = info.pMappedData();
			return new Buf(pBuf.get(0), pAlloc.get(0), raw + offset, mapped == 0 ? 0 : mapped + offset, size, offset);
		}
	}

	public record Img(long image, long allocation, long view, int width, int height, int format) {
		public void free() {
			vkDestroyImageView(vk, view, null);
			vmaDestroyImage(vma, image, allocation);
		}
	}

	/** Device-local 2D storage image (one mip), also sampled (NGX reads its inputs). Callers move it to VK_IMAGE_LAYOUT_GENERAL before use. */
	public static Img storageImage(int width, int height, int format) {
		return storageImage(width, height, 1, format);
	}

	/** The same, 3D when depth > 1 (the cloud noise). */
	public static Img storageImage(int width, int height, int depth, int format) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer pImage = s.mallocLong(1);
			PointerBuffer pAlloc = s.mallocPointer(1);
			VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(depth > 1 ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D).format(format)
				.mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
				.usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
			ci.extent().set(width, height, depth);
			check(vmaCreateImage(vma, ci, VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO), pImage, pAlloc, null), "vmaCreateImage");
			LongBuffer pView = s.mallocLong(1);
			VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(pImage.get(0)).viewType(depth > 1 ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(format);
			vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			check(vkCreateImageView(vk, vi, null, pView), "image view");
			return new Img(pImage.get(0), pAlloc.get(0), pView.get(0), width, height, format);
		}
	}

	public static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
