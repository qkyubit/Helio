package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.helio.rt.Scene;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;

	// The loop that schedules dirty sections for compiling: include off-screen sections the RT scene needs.
	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> helio$alsoCompileHidden(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		return Scene.withHiddenSections(levelRenderer.viewArea(), visible);
	}
}
