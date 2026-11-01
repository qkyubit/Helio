package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.helio.vk.Reflex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public class VulkanCommandEncoderMixin {
	@Inject(method = "submit", at = @At("TAIL"))
	private void helio$submitted(CallbackInfo ci) {
		Reflex.submitted();
	}
}
