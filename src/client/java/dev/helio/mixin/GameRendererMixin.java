package dev.helio.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.helio.rt.PathTracer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	// The projection vanilla actually draws the level with (view bobbing, nausea warp included).
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render"))
	private void helio$captureProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projectionMatrix) {
		PathTracer.levelProjection.set(projectionMatrix);
	}
}
