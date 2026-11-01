package dev.helio.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.rt.SunShadowSpike;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow @Final private LevelRenderState levelRenderState;

	// After the level frame graph: main colour + depth hold the finished world, GUI not drawn yet.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute", shift = At.Shift.AFTER))
	private void helio$afterLevel(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		SunShadowSpike.afterLevel(cameraState.pos, cameraState.viewRotationMatrix, levelRenderState.skyRenderState.sunAngle);
		FrameGenSpike.afterLevel(cameraState.pos, cameraState.viewRotationMatrix, SunShadowSpike.levelProjection, cameraState.depthFar);
	}
}
