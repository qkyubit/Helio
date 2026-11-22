package dev.helio.mixin;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.helio.ngx.FrameGenSpike;
import dev.helio.rt.Entities;
import dev.helio.rt.PathTracer;
import dev.helio.rt.Scene;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
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
	@Unique private static ProjectionMatrixBuffer helio$unjittered;
	@Unique private boolean helio$translucentTerrainDrawn;
	@Shadow private GpuSampler chunkLayerSampler;
	@Shadow @Final private TextureManager textureManager;

	// Translucent terrain already drawn before the trace (helio$traceAndReplay): not again in the replay.
	@WrapOperation(method = "executeClassicTransparency", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void helio$skipDrawnTranslucents(ChunkSectionsToRender sections, ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler,
		GpuTextureView atlas, boolean wireframe, Operation<Void> original) {
		if (!helio$translucentTerrainDrawn) original.call(sections, group, pass, sampler, atlas, wireframe);
	}

	@Shadow
	private void executeClassicTransparency(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass renderPass) {}

	@Inject(method = "render", at = @At("HEAD"))
	private void helio$beginFrame(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		PathTracer.beginFrame(cameraState, levelRenderState);
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
		// Translucent terrain (only past the RT distance: the tracer draws the nearer) goes on before the trace,
		// still jittered: it's shaded and anti-aliased with the rest of vanilla's picture. Drawn after, it kept
		// vanilla's look and jittered over the upscaled frame. The depth before it tells the tracer where it lies.
		if (helio$deferredSections != null && PathTracer.active()) {
			PathTracer.copyOpaqueDepth();
			RenderTarget target = gameRenderer.mainRenderTarget();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Helio far translucent terrain", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				helio$deferredSections.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, chunkLayerSampler,
					textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(), levelRenderState.renderWireframeTerrain);
			}
			helio$translucentTerrainDrawn = true;
		}
		PathTracer.run();
		RenderTarget main = gameRenderer.mainRenderTarget();
		// Terrain was drawn jittered for DLSS (PathTracer.jitterLevelProjection); what's drawn from here on
		// (entities, particles, translucents, outlines) isn't, or it would wobble over the upscaled picture.
		if (PathTracer.vanillaJittered()) {
			if (helio$unjittered == null) helio$unjittered = new ProjectionMatrixBuffer("Helio unjittered level");
			RenderSystem.setProjectionMatrix(helio$unjittered.getBuffer(PathTracer.levelProjection), ProjectionType.PERSPECTIVE);
		}
		try {
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Helio solid features", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				helio$deferredFeatures.executeSolid(pass);
			}
			// Before translucents: the composite puts traced colour back wherever vanilla's depth lies behind traced
			// water or glass, and weather, clouds and translucent particles write no depth there.
			PathTracer.compositeEntities();
			if (helio$deferredSections != null) {
				helio$replaying = true;
				try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
					.createRenderPass(() -> "Helio classic translucents", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
					RenderSystem.bindDefaultUniforms(pass);
					executeClassicTransparency(helio$deferredSections, helio$deferredFeatures, pass);
				}
			}
		} finally {
			helio$replaying = false;
			helio$translucentTerrainDrawn = false;
			helio$deferredSections = null;
			helio$deferredFeatures = null;
		}
	}

	// The first-person body goes in with render types vanilla won't draw (Entities.bodyCollector).
	@WrapOperation(method = "submitEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
	private void helio$submitBody(EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState camera, double x, double y, double z,
		PoseStack poseStack, SubmitNodeCollector output, Operation<Void> original) {
		if (state != Entities.firstPersonBody) original.call(dispatcher, state, camera, x, y, z, poseStack, output);
		else if (!Entities.firstPersonHeadOnly) original.call(dispatcher, state, camera, x, y, z, poseStack, Entities.bodyCollector(output));
		else { // another mod's first-person body: as it is, and again for the head's shadow
			original.call(dispatcher, state, camera, x, y, z, poseStack, output);
			original.call(dispatcher, state, camera, x, y, z, poseStack, Entities.bodyCollector(output));
		}
	}

	// Inside the RT distance the path tracer draws translucent terrain itself (reflected and refracted).
	@WrapOperation(method = "extractSectionDrawGroups", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionMesh;getSectionDraw(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionMesh$SectionDraw;"))
	private SectionMesh.SectionDraw helio$skipTracedTranslucents(SectionMesh mesh, ChunkSectionLayer layer, Operation<SectionMesh.SectionDraw> original,
		@Local SectionRenderDispatcher.RenderSection section) {
		if (layer.translucent() && Scene.tracesTranslucent(section.getSectionNode(), levelRenderState.cameraRenderState.pos)) return null;
		return original.call(mesh, layer);
	}

	// After the level frame graph: main colour + depth hold the finished world, GUI not drawn yet.
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute", shift = At.Shift.AFTER))
	private void helio$afterLevel(CallbackInfo ci, @Local(argsOnly = true) CameraRenderState cameraState) {
		PathTracer.afterLevel();
		FrameGenSpike.afterLevel(cameraState.pos, cameraState.viewRotationMatrix, PathTracer.levelProjection, cameraState.depthFar);
	}
}
