package dev.helio.sodium;

import dev.helio.rt.FaceRotation;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import dev.helio.rt.SodiumMeshes;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Water is marked, lava glows: as FluidRendererMixin does for vanilla's fluids.
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public class DefaultFluidRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void helio$fluid(LevelSlice level, BlockState state, FluidState fluid, BlockPos pos, BlockPos offset, TranslucentGeometryCollector collector,
		ChunkModelBuilder builder, Material material, ColorProvider<FluidState> colors, FluidModel model, CallbackInfo ci) {
		SodiumMeshes.fluid(state, fluid, SodiumMeshes.layerOf(material.pass == DefaultTerrainRenderPasses.SOLID, material.pass == DefaultTerrainRenderPasses.TRANSLUCENT));
		FaceRotation.fluid(pos, fluid);
	}

	// Random Block Rotation: still water's and lava's texture turns (FaceRotation). Turned back after: Sodium writes the same
	// quad again for its back side.
	@Inject(method = "writeQuad", at = @At("HEAD"))
	private void helio$rotateLava(ChunkModelBuilder builder, TranslucentGeometryCollector collector, Material material, BlockPos offset,
		ModelQuadView quad, ModelQuadFacing facing, boolean flip, CallbackInfo ci) {
		turn(quad, false);
	}

	@Inject(method = "writeQuad", at = @At("RETURN"))
	private void helio$rotateLavaBack(ChunkModelBuilder builder, TranslucentGeometryCollector collector, Material material, BlockPos offset,
		ModelQuadView quad, ModelQuadFacing facing, boolean flip, CallbackInfo ci) {
		turn(quad, true);
	}

	@Unique
	private static void turn(ModelQuadView quad, boolean back) {
		for (int i = 0; i < 4; i++) {
			float u = quad.sodium$getTexU(i), v = quad.sodium$getTexV(i);
			int turns = FaceRotation.fluidTurns(u, v);
			if (turns == 0) continue;
			if (back) turns = 4 - turns;
			TextureAtlasSprite still = FaceRotation.still();
			((ModelQuadViewMutable) quad).setTexU(i, FaceRotation.u(u, v, still, turns));
			((ModelQuadViewMutable) quad).setTexV(i, FaceRotation.v(u, v, still, turns));
		}
	}
}
