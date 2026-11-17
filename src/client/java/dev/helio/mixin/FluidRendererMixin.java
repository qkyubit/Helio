package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.helio.rt.FaceRotation;
import dev.helio.rt.Scene;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
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
	private void helio$fluidEmission(CallbackInfo ci, @Local(argsOnly = true) BlockState state, @Local(argsOnly = true) FluidState fluid,
		@Local(argsOnly = true) BlockPos pos) {
		Scene.FLUID_EMISSION.set(fluid.is(FluidTags.WATER) ? Scene.WATER_MARK : state.getLightEmission());
		FaceRotation.fluid(pos, fluid);
	}

	// Random Block Rotation: still water's and lava's texture turns (FaceRotation).
	@WrapOperation(method = "vertex", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"))
	private void helio$rotateLava(VertexConsumer builder, float x, float y, float z, int color, float u, float v, int overlay, int light,
		float nx, float ny, float nz, Operation<Void> original) {
		int turns = FaceRotation.fluidTurns(u, v);
		if (turns != 0) {
			TextureAtlasSprite still = FaceRotation.still();
			float ru = FaceRotation.u(u, v, still, turns);
			v = FaceRotation.v(u, v, still, turns);
			u = ru;
		}
		original.call(builder, x, y, z, color, u, v, overlay, light, nx, ny, nz);
	}

	// Lava glows, water is marked: same alpha encoding of the emission level as BlockModelLighterMixin.
	@ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int helio$encodeEmission(int color) {
		return Scene.compilingUnshaded() ? Scene.withEmission(color, Scene.FLUID_EMISSION.get()) : color;
	}
}
