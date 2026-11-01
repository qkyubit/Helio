package dev.helio.mixin;

import dev.helio.rt.Entities;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class RenderTypeGroupMixin {
	// The only place a staged draw and its render type meet.
	@Inject(method = "getOrAddDraw", at = @At("RETURN"))
	private void helio$tagDraw(RenderType renderType, CallbackInfoReturnable<StagedVertexBuffer.Draw> cir) {
		Entities.tag(cir.getReturnValue(), renderType);
	}
}
