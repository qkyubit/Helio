package dev.helio.mixin;

import dev.helio.rt.Entities;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public class PreparedRenderTypeMixin {
	// The first-person body is captured for shadows, never drawn (Entities.firstPersonBody).
	@Inject(method = "draw", at = @At("HEAD"), cancellable = true)
	private void helio$skipBody(CallbackInfo ci) {
		if (((PreparedRenderType) (Object) this).name().startsWith(Entities.BODY_PREFIX)) ci.cancel();
	}
}
