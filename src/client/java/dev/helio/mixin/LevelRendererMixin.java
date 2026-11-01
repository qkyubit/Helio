package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
		PathTracer.beginFrame(cameraState, levelRenderState.skyRenderState);
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

	/*
	 * The path tracer replaces opaque terrain only. Vanilla draws terrain, then solid features (entities,
	 * particles, sign text, overlays) and, with classic transparency, translucents, all in one render pass
	 * where compute can't run. So the features and classic translucents are held back, and once that pass
	 * has ended Helio traces, then replays them in a pass of its own over the traced terrain, and puts the
	 * traced entity colour back over vanilla's entities (which it lets draw for their depth).
	 */
	@WrapOperation(method = "executeSolid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private void helio$deferSolidFeatures(FeatureRenderDispatcher.PreparedFrame features, RenderPass renderPass, Operation<Void> original) {
		if (!PathTracer.active()) {
			original.call(features, renderPass);
			return;
		}
		helio$deferredFeatures = features;
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"), cancellable = true)
	private void helio$deferClassicTransparency(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features, RenderPass renderPass, CallbackInfo ci) {
		if (helio$replaying || helio$deferredFeatures == null) return;
		helio$deferredSections = sections;
		ci.cancel();
	}

	// Improved Transparency: the opaque render pass has ended; OIT translucents come next.
	@Inject(method = "executeOit", at = @At("HEAD"))
	private void helio$traceBeforeOit(CallbackInfo ci) {
		helio$traceAndReplay();
	}

	// Classic transparency: the combined pass has ended.
	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void helio$traceBeforeOutline(CallbackInfo ci) {
		helio$traceAndReplay();
	}

	@Unique
	private void helio$traceAndReplay() {
		if (helio$deferredFeatures == null) return;
		PathTracer.run();
		RenderTarget main = gameRenderer.mainRenderTarget();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
			.createRenderPass(() -> "Helio solid features", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			helio$deferredFeatures.executeSolid(pass);
			if (helio$deferredSections != null) {
				helio$replaying = true;
				executeClassicTransparency(helio$deferredSections, helio$deferredFeatures, pass);
			}
		} finally {
			helio$replaying = false;
			helio$deferredSections = null;
			helio$deferredFeatures = null;
		}
		PathTracer.compositeEntities();
	}

	// After the level frame graph: main colour + depth hold the finished world, GUI not drawn yet.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute", shift = At.Shift.AFTER))
	private void helio$afterLevel(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		PathTracer.afterLevel();
		FrameGenSpike.afterLevel(cameraState.pos, cameraState.viewRotationMatrix, PathTracer.levelProjection, cameraState.depthFar);
	}
}
