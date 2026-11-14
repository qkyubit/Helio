package dev.helio.mixin;

import dev.helio.rt.PathTracer;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's clouds stay off while Helio draws the sky: pathtrace.comp traces its own (M6 step 4). */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {
	@Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
	private void helio$ownClouds(CallbackInfo ci) {
		if (PathTracer.drawsSky()) ci.cancel();
	}
}
