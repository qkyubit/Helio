package dev.helio.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.helio.rt.Scene;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(BlockModelLighter.class)
public class BlockModelLighterMixin {
	// Vanilla bakes AO and per-face shading into section vertex colours; the path tracer lights for real,
	// so sections compiled for it keep only the biome tint (multiplied in after this). Light levels stay.
	// The block's emission level goes into alpha, for glowing surfaces; foliage is marked the same way.
	@Inject(method = {"prepareQuadAmbientOcclusion", "prepareQuadFlat"}, at = @At("TAIL"))
	private void helio$unshaded(CallbackInfo ci, @Local(argsOnly = true) QuadInstance quad, @Local(argsOnly = true) BlockState state) {
		if (Scene.compilingUnshaded()) quad.setColor(Scene.withEmission(-1, Scene.isFoliage(state) ? Scene.FOLIAGE_MARK : state.getLightEmission()));
	}
}
