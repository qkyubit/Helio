package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.ngx.PacedPresenter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanQueue.class)
public class VulkanQueueMixin {
	// Waiting for the queue to drain (before swapchain or resource changes) means the frame-generation pacer's
	// presents must be out of the way first, its own queue included.
	@Inject(method = "waitIdle", at = @At("HEAD"))
	private void helio$awaitPacer(CallbackInfo ci) {
		FrameGenSpike.awaitPacedPresent();
		PacedPresenter.waitIdle();
	}
}
