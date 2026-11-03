package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.helio.ngx.FrameGenSpike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanQueue.class)
public class VulkanQueueMixin {
	// Waiting for the queue to drain means the frame-generation pacer's present must be out of the way first.
	@Inject(method = "waitIdle", at = @At("HEAD"))
	private void helio$awaitPacer(CallbackInfo ci) {
		FrameGenSpike.awaitPacedPresent();
	}
}
