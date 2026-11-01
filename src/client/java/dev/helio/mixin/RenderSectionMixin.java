package dev.helio.mixin;

import dev.helio.rt.Scene;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionMixin {
	@Inject(method = "reset", at = @At("HEAD"))
	private void helio$removeGeometry(CallbackInfo ci) {
		Scene.remove(((SectionRenderDispatcher.RenderSection) (Object) this).getSectionNode());
	}
}
