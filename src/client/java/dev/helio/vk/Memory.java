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

	public record Buf(long buffer, long allocation, long address, long mapped, long size) {
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

	/** Buffer with a device address; host-visible ones are persistently mapped. */
	public static Buf buffer(long size, int usage, boolean hostVisible) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			LongBuffer pBuf = s.mallocLong(1);
			PointerBuffer pAlloc = s.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(s);
			check(vmaCreateBuffer(vma,
				VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT),
				VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO)
					.flags(hostVisible ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0),
				pBuf, pAlloc, info), "vmaCreateBuffer");
			long address = vkGetBufferDeviceAddress(vk, VkBufferDeviceAddressInfo.calloc(s).sType$Default().buffer(pBuf.get(0)));
			// AS storage needs 256-byte alignment; VMA's buffer alignment already covers it on NVIDIA.
			if ((address & 255) != 0) throw new IllegalStateException("buffer address not 256-aligned: 0x" + Long.toHexString(address));
			allocated.addAndGet(size);
			return new Buf(pBuf.get(0), pAlloc.get(0), address, info.pMappedData(), size);
		}
	}

	public static void check(int result, String what) {
		if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
	}
}
