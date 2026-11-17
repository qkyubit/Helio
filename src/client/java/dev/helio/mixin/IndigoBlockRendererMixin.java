package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.helio.rt.FaceRotation;
import dev.helio.rt.Scene;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * With Fabric API's renderer (Indigo) installed, terrain is meshed by Indigo instead of vanilla's
 * BlockModelLighter: same job as BlockModelLighterMixin there. Indigo still computes light levels; only the
 * AO and face shading it bakes into vertex colour is undone (the biome tint is applied after), and the block's
 * mark (Scene.mark) goes into alpha.
 */
@Mixin(AltModelBlockRendererImpl.class)
public class IndigoBlockRendererMixin {
	@Shadow private BlockState blockState;
	@Shadow private BlockPos pos;

	// Random Block Rotation (FaceRotation): before anything else reads the quad's UVs.
	@Inject(method = "transform", at = @At("HEAD"))
	private void helio$rotate(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
		FaceRotation.rotate(quad, blockState, pos);
	}

	@WrapOperation(method = "transform", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl;shadeQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;ZZZ)V"))
	private void helio$unshaded(AltModelBlockRendererImpl self, MutableQuadViewImpl quad, boolean ao, boolean emissive, boolean vanillaShade, Operation<Void> original) {
		if (!Scene.compilingUnshaded()) {
			original.call(self, quad, ao, emissive, vanillaShade);
			return;
		}
		int c0 = quad.color(0), c1 = quad.color(1), c2 = quad.color(2), c3 = quad.color(3);
		original.call(self, quad, ao, emissive, vanillaShade);
		int mark = Scene.mark(blockState);
		quad.color(0, Scene.withEmission(c0, mark)).color(1, Scene.withEmission(c1, mark))
			.color(2, Scene.withEmission(c2, mark)).color(3, Scene.withEmission(c3, mark));
	}
}
