package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.helio.rt.Scene;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void helio$begin(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		Scene.COMPILING.set(Boolean.TRUE);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void helio$end(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		Scene.COMPILING.set(Boolean.FALSE);
	}

	// Each quad picks its layer from its texture: high-resolution leaves with soft edges land in the translucent
	// layer, where the path tracer would take them for glass or water. Foliage stays cutout (alpha-tested).
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void helio$tesselate(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
		BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
		Scene.TESSELATING_FOLIAGE.set(Scene.compilingUnshaded() && Scene.isFoliage(state));
		Scene.TESSELATING_GLASS.set(Scene.compilingUnshaded() && Scene.isGlass(state));
		try {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} finally {
			Scene.TESSELATING_FOLIAGE.set(Boolean.FALSE);
			Scene.TESSELATING_GLASS.set(Boolean.FALSE);
		}
	}

	@ModifyVariable(method = "getOrBeginLayer", at = @At("HEAD"), argsOnly = true)
	private ChunkSectionLayer helio$foliageCutout(ChunkSectionLayer layer) {
		if (layer == ChunkSectionLayer.TRANSLUCENT && Scene.TESSELATING_FOLIAGE.get()) return ChunkSectionLayer.CUTOUT;
		return layer == ChunkSectionLayer.CUTOUT && Scene.TESSELATING_GLASS.get() ? ChunkSectionLayer.TRANSLUCENT : layer;
	}
}
