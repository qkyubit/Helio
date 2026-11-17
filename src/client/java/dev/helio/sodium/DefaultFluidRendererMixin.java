package dev.helio.sodium;

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
	}
}
