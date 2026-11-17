package dev.helio.sodium;

import dev.helio.rt.SodiumMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Which block (for Scene.mark) and which pass each quad Sodium buffers belongs to.
@Mixin(value = BlockRenderer.class, remap = false)
public class BlockRendererMixin {
	@Inject(method = "prepare", at = @At("HEAD"))
	private void helio$level(ChunkBuildBuffers buffers, LevelSlice level, TranslucentGeometryCollector collector, CallbackInfo ci) {
		SodiumMeshes.level(level);
	}

	@Inject(method = "renderModel", at = @At("HEAD"))
	private void helio$block(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		SodiumMeshes.block(state);
	}

	@Inject(method = "bufferQuad", at = @At("HEAD"))
	private void helio$pass(MutableQuadViewImpl quad, float[] brightness, Material material, CallbackInfo ci) {
		SodiumMeshes.layer(SodiumMeshes.layerOf(material.pass == DefaultTerrainRenderPasses.SOLID, material.pass == DefaultTerrainRenderPasses.TRANSLUCENT));
	}
}
