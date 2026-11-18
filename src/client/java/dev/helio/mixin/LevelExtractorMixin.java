package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.helio.rt.Entities;
import dev.helio.rt.PathTracer;
import dev.helio.rt.Scene;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;

	// The loop that schedules dirty sections for compiling: include off-screen sections the RT scene needs.
	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> helio$alsoCompileHidden(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		return Scene.withHiddenSections(levelRenderer.viewArea(), visible);
	}

	// First person: vanilla leaves the player's own body out, but it should still cast a shadow.
	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void helio$extractFirstPersonBody(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		Entities.firstPersonBody = null;
		if (!PathTracer.active() || camera.isDetached() || !(camera.entity() instanceof LocalPlayer player) || player.isSleeping()) return;
		// A mod already draws the body in first person (First-person Model): it's a plain entity then, seen, lit and
		// casting its shadow; a second, hidden copy of Helio's in the same place shadowed it black.
		for (EntityRenderState other : output.entityRenderStates)
			if (other instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState a && a.id == player.getId()) return;
		EntityRenderState state = levelRenderer.entityRenderDispatcher().extractEntity(player, deltaTracker.getGameTimeDeltaPartialTick(true));
		output.entityRenderStates.add(state);
		Entities.firstPersonBody = state;
	}
}
