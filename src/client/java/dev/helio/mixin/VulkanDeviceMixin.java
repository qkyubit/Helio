package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.helio.Helio;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public class VulkanDeviceMixin {
	@Shadow @Final private FeatureSet enabledFeatures;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void helio$onCreated(CallbackInfo ci) {
		Helio.onDeviceCreated((VulkanDevice) (Object) this, enabledFeatures);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void helio$onClosing(CallbackInfo ci) {
		Helio.onDeviceClosing();
	}
}
