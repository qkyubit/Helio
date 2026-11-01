package dev.helio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.helio.vk.HelioFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(VulkanFeatureSets.class)
public class VulkanFeatureSetsMixin {
	// Vanilla returns a fresh mutable ObjectOpenHashSet each call.
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"))
	private static void helio$addFeatureSets(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		cir.getReturnValue().addAll(HelioFeatures.ALL);
	}
}
