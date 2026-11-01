package dev.helio.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.rt.Entities;
import dev.helio.rt.PathTracer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalDouble;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow @Final private LevelRenderState levelRenderState;
	@Shadow @Final private GameRenderer gameRenderer;

	@Unique private ChunkSectionsToRender helio$deferredSections;
	@Unique private FeatureRenderDispatcher.PreparedFrame helio$deferredFeatures;
	@Unique private boolean helio$replaying;

	@Shadow
	private void executeClassicTransparency(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass) {}

	@Inject(method = "render", at = @At("HEAD"))
	private void helio$beginFrame(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		PathTracer.beginFrame(cameraState.pos, cameraState.viewRotationMatrix, levelRenderState.skyRenderState.sunAngle);
	}

	// The level's features (entities, block entities, items) are prepared and uploaded inside this call.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame"))
	private void helio$beginEntityCapture(CallbackInfo ci) {
		Entities.begin();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame", shift = At.Shift.AFTER))
	private void helio$endEntityCapture(CallbackInfo ci) {
		Entities.end();
	}

	// Improved Transparency: the opaque render pass has ended; translucents come next.
	@Inject(method = "executeOit", at = @At("HEAD"))
	private void helio$pathTraceBeforeOit(CallbackInfo ci) {
		PathTracer.run();
	}

	// Classic transparency draws inside the opaque render pass, where compute can't run: hold it back...
	@Inject(method = "executeClassicTransparency", at = @At("HEAD"), cancellable = true)
	private void helio$deferClassicTransparency(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features, RenderPass renderPass, CallbackInfo ci) {
		if (helio$replaying || !PathTracer.active()) return;
		helio$deferredSections = sections;
		helio$deferredFeatures = features;
		ci.cancel();
	}

	// ...then, once that pass has ended, path trace and replay it in a pass of its own.
	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void helio$pathTraceBeforeClassicTransparency(CallbackInfo ci) {
		if (helio$deferredSections == null) return;
		PathTracer.run();
		RenderTarget main = gameRenderer.mainRenderTarget();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
			.createRenderPass(() -> "Helio translucent", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			helio$replaying = true;
			executeClassicTransparency(helio$deferredSections, helio$deferredFeatures, pass);
		} finally {
			helio$replaying = false;
			helio$deferredSections = null;
			helio$deferredFeatures = null;
		}
	}

	// After the level frame graph: main colour + depth hold the finished world, GUI not drawn yet.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute", shift = At.Shift.AFTER))
	private void helio$afterLevel(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		PathTracer.afterLevel();
		FrameGenSpike.afterLevel(cameraState.pos, cameraState.viewRotationMatrix, PathTracer.levelProjection, cameraState.depthFar);
	}
}
