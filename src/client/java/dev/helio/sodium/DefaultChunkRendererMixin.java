package dev.helio.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.helio.rt.Scene;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Inside the RT distance the path tracer draws water, glass and ice itself: Sodium's translucent pass skips those
// sections (no faces to draw), as LevelRendererMixin skips vanilla's.
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class DefaultChunkRendererMixin {
	@WrapOperation(method = "fillCommandBuffer", at = @At(value = "INVOKE",
		target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/SectionRenderDataUnsafe;getSliceMask(J)I"))
	private static int helio$skipTraced(long data, Operation<Integer> original, @Local(argsOnly = true) TerrainRenderPass pass,
		@Local(index = 15) int x, @Local(index = 17) int z) {
		if (pass.isTranslucent() && Scene.tracesTranslucent(x, z)) return 0;
		return original.call(data);
	}
}
