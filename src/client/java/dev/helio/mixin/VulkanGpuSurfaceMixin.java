package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import dev.helio.Helio;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.vk.Reflex;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {
	@Shadow @Final private VkQueue presentQueue;
	@Shadow private long swapchain;
	@Shadow @Final private LongList swapchainImages;
	@Shadow private int swapchainWidth;
	@Shadow private int swapchainHeight;
	@Shadow @Final private long surface;
	@Shadow @Final private int swapchainImageFormat;
	@Shadow private boolean swapchainSuboptimal;
	@Shadow private boolean swapchainOutOfDate;
	/** Frame generation took the swapchain this frame: Mojang's acquire was skipped (it may still be needed). */
	@Unique private boolean helio$skippedAcquire, helio$forceAcquire;

	// Reflex: latency mode must be requested when the swapchain is created.
	@WrapOperation(method = "configure", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int helio$latencySwapchain(VkDevice device, VkSwapchainCreateInfoKHR info, VkAllocationCallbacks allocator, LongBuffer handle, Operation<Integer> original) {
		try (MemoryStack s = MemoryStack.stackPush()) {
			info.pNext(Reflex.swapchainPNext(s, info.pNext()));
			int result = original.call(device, info, allocator, handle);
			if (result >= 0) Reflex.onSwapchainCreated(device, handle.get(0));
			return result;
		}
	}

	// While frame generation runs, its pacer presents every frame from a queue of its own (PacedPresenter), and
	// Mojang leaves the swapchain alone: its blit waits for its acquired image to come off the screen, and
	// everything on its queue after that (the next frame's rendering too) would wait with it.
	@Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
	private void helio$skipAcquire(CallbackInfo ci) {
		if (helio$forceAcquire) {
			helio$forceAcquire = false;
			return;
		}
		if (!swapchainOutOfDate && FrameGenSpike.takesOverSwapchain()) {
			helio$skippedAcquire = true;
			ci.cancel();
		}
	}

	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void helio$generateFrames(CommandEncoderBackend encoder, GpuTextureView textureView, CallbackInfo ci) {
		boolean skipped = helio$skippedAcquire;
		helio$skippedAcquire = false;
		boolean taken = FrameGenSpike.takeFrame((VulkanCommandEncoder) encoder, textureView, swapchain, swapchainImages,
			swapchainWidth, swapchainHeight, surface, swapchainImageFormat, !skipped);
		if (taken) {
			ci.cancel();
		} else if (skipped) {
			// Frame generation stopped this frame (a menu opened): Mojang presents it after all.
			helio$forceAcquire = true;
			try {
				((VulkanGpuSurface) (Object) this).acquireNextTexture();
			} catch (SurfaceException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	// Frame generation shows two images per frame, and its pacer keeps them queued for up to a frame while the
	// render thread goes ahead: 3 images (Mojang's choice) run out and acquiring stalls for seconds.
	@ModifyArg(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private int helio$moreImages(int count, @Local VkSurfaceCapabilitiesKHR capabilities) {
		int max = capabilities.maxImageCount(); // 0: no limit
		int n = max == 0 ? Math.max(count, 8) : Math.max(count, Math.min(8, max));
		Helio.LOG.info("Swapchain: {} images (surface allows {} to {})", n, capabilities.minImageCount(), max);
		return n;
	}

	// Frame generation's pacer acquires and presents on this swapchain from its own thread, and Vulkan wants the
	// swapchain externally synchronised: poll under the queue lock instead of blocking while holding it.
	@WrapOperation(method = "acquireNextTexture", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int helio$acquire(VkDevice device, long chain, long timeout, long semaphore, long fence, IntBuffer index, Operation<Integer> original) {
		long start = System.nanoTime(), deadline = start + timeout;
		try {
			while (true) {
				int r;
				synchronized (FrameGenSpike.QUEUE_LOCK) {
					r = original.call(device, chain, 0L, semaphore, fence, index);
				}
				if (r != VK10.VK_NOT_READY && r != VK10.VK_TIMEOUT) return r;
				if (System.nanoTime() - deadline > 0) return VK10.VK_TIMEOUT;
				java.util.concurrent.locks.LockSupport.parkNanos(100_000);
			}
		} finally {
			FrameGenSpike.acquireWaitNanos += System.nanoTime() - start;
		}
	}

	@Inject(method = "present", at = @At("HEAD"), cancellable = true)
	private void helio$presentStart(CallbackInfo ci) {
		Reflex.presentStart();
		if (FrameGenSpike.presentsRealFrameItself()) {
			FrameGenSpike.afterPresent(presentQueue); // hands this frame's presents to the pacer
			Reflex.presentEnd();
			if (FrameGenSpike.swapchainSuboptimal()) swapchainSuboptimal = true; // Minecraft reconfigures it
			ci.cancel();
		}
	}

	// Mojang's present carries this frame's present ID.
	@WrapOperation(method = "present", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int helio$presentId(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		FrameGenSpike.awaitPacedPresent(); // frame generation just stopped: its last presents go first
		long id = Reflex.presentId();
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (id != 0) info.pNext(VkPresentIdKHR.calloc(s).sType$Default().pNext(info.pNext()).swapchainCount(1).pPresentIds(s.longs(id)).address());
			synchronized (FrameGenSpike.QUEUE_LOCK) {
				return original.call(queue, info);
			}
		}
	}

	@Inject(method = "present", at = @At("TAIL"))
	private void helio$presentRealFrame(CallbackInfo ci) {
		FrameGenSpike.afterPresent(presentQueue);
		Reflex.presentEnd();
	}
}
