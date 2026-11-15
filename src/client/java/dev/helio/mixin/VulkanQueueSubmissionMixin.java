package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.vk.Reflex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkLatencySubmissionPresentIdNV;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Reflex attributes GPU work to a frame through VkLatencySubmissionPresentIdNV on each submit.
@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanQueue$Submission")
public class VulkanQueueSubmissionMixin {
	@WrapOperation(method = "close", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/KHRSynchronization2;vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I"))
	private int helio$tagFrame(VkQueue queue, VkSubmitInfo2.Buffer submits, long fence, Operation<Integer> original) {
		long id = Reflex.submissionId();
		try (MemoryStack s = MemoryStack.stackPush()) {
			if (id != 0) for (VkSubmitInfo2 submit : submits) {
				submit.pNext(VkLatencySubmissionPresentIdNV.calloc(s).sType$Default().pNext(submit.pNext()).presentID(id).address());
			}
			synchronized (FrameGenSpike.QUEUE_LOCK) { // the frame-generation pacer presents on this queue too
				return original.call(queue, submits, fence);
			}
		}
	}
}
