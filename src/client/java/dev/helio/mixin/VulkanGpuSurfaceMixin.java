package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.vk.Reflex;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.LongBuffer;

@Mixin(VulkanGpuSurface.class)
public class VulkanGpuSurfaceMixin {
	@Shadow @Final private VkQueue presentQueue;
	@Shadow private long swapchain;
	@Shadow @Final private LongList swapchainImages;
	@Shadow private int swapchainWidth;
	@Shadow private int swapchainHeight;

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

	@ModifyVariable(method = "blitFromTexture", at = @At("HEAD"), argsOnly = true)
	private GpuTextureView helio$interpolatedFrame(GpuTextureView textureView, @Local(argsOnly = true) CommandEncoderBackend encoder) {
		return FrameGenSpike.beforeBlit((VulkanCommandEncoder) encoder, textureView);
	}

	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void helio$queueRealFrame(CommandEncoderBackend encoder, GpuTextureView textureView, CallbackInfo ci) {
		FrameGenSpike.afterBlit((VulkanCommandEncoder) encoder, swapchain, swapchainImages, swapchainWidth, swapchainHeight);
	}

	// Frame generation shows two images per frame, and its pacer keeps them queued for up to a frame while the
	// render thread goes ahead: 3 images (Mojang's choice) run out and acquiring stalls for seconds.
	@ModifyArg(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"))
	private int helio$moreImages(int count, @Local VkSurfaceCapabilitiesKHR capabilities) {
		int max = capabilities.maxImageCount(); // 0: no limit
		return max == 0 ? Math.max(count, 8) : Math.max(count, Math.min(8, max));
	}

	@Inject(method = "present", at = @At("HEAD"))
	private void helio$presentStart(CallbackInfo ci) {
		Reflex.presentStart();
	}

	// Mojang's present carries this frame's present ID; when it shows the interpolated frame, the pacer presents it.
	@WrapOperation(method = "present", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int helio$presentId(VkQueue queue, VkPresentInfoKHR info, Operation<Integer> original) {
		if (FrameGenSpike.presentsRealFrameItself()) return FrameGenSpike.presentLater(queue, info); // paced with the real frame
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
