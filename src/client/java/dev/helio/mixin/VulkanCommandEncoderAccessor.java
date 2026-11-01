package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	/** Mojang's current frame command buffer (begun lazily). Must not be called inside a render pass. */
	@Invoker("commandBuffer")
	VkCommandBuffer helio$commandBuffer();
}
