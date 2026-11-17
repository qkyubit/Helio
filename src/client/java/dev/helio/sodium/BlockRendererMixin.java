package dev.helio.sodium;

import dev.helio.rt.FaceRotation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector2f;
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
		FaceRotation.block(state, pos);
	}

	@Inject(method = "bufferQuad", at = @At("HEAD"))
	private void helio$pass(MutableQuadViewImpl quad, float[] brightness, Material material, CallbackInfo ci) {
		// Random Block Rotation (FaceRotation), before Sodium reads the UVs.
		int turns = FaceRotation.currentTurns(quad.getNominalFace());
		if (turns != 0) {
			Vector2f uv = new Vector2f();
			float cu = 0, cv = 0;
			for (int i = 0; i < 4; i++) {
				quad.copyUv(i, uv);
				cu += uv.x / 4;
				cv += uv.y / 4;
			}
			TextureAtlasSprite sprite = FaceRotation.spriteAt(cu, cv);
			if (sprite != null) for (int i = 0; i < 4; i++) {
				quad.copyUv(i, uv);
				quad.setUV(i, FaceRotation.u(uv.x, uv.y, sprite, turns), FaceRotation.v(uv.x, uv.y, sprite, turns));
			}
		}
		SodiumMeshes.layer(SodiumMeshes.layerOf(material.pass == DefaultTerrainRenderPasses.SOLID, material.pass == DefaultTerrainRenderPasses.TRANSLUCENT));
	}
}
