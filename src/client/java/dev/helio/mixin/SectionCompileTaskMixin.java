package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.helio.rt.Scene;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class SectionCompileTaskMixin {
	// On the compile worker, while the meshes are still in CPU memory: hand a copy to the RT scene.
	@ModifyExpressionValue(method = "doTask", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;compile"))
	private SectionCompiler.Results helio$captureGeometry(SectionCompiler.Results results, @Local SectionPos sectionPos) {
		Scene.submit(sectionPos.asLong(), results.renderedLayers);
		return results;
	}
}
