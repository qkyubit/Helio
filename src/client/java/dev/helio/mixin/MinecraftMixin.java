package dev.helio.mixin;

import dev.helio.vk.Reflex;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	// Frame start, before input and game ticks: where Reflex sleeps.
	@Inject(method = "runTick", at = @At("HEAD"))
	private void helio$frameStart(CallbackInfo ci) {
		Reflex.frameStart();
	}

	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void helio$renderStart(CallbackInfo ci) {
		Reflex.renderStart();
	}
}
