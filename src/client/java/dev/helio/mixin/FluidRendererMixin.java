package dev.helio.mixin;

import dev.helio.rt.Scene;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.sugar.Local;

@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Inject(method = "tesselate", at = @At("HEAD"))
	private void helio$fluidEmission(CallbackInfo ci, @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) FluidState fluid) {
		Scene.FLUID_EMISSION.set(fluid.is(FluidTags.WATER) ? Scene.WATER_MARK : state.getLightEmission());
	}

	// Lava glows, water is marked: same alpha encoding of the emission level as BlockModelLighterMixin.
	@ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int helio$encodeEmission(int color) {
		return Scene.compilingUnshaded() ? Scene.withEmission(color, Scene.FLUID_EMISSION.get()) : color;
	}
}
