package dev.helio.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.helio.rt.Entities;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(StagedVertexBuffer.class)
public class StagedVertexBufferMixin {
	// Last moment the draws' CPU vertex data exists.
	@Inject(method = "uploadDrawsToBuffers", at = @At("HEAD"))
	private void helio$capture(CallbackInfo ci, @Local(argsOnly = true) List<StagedVertexBuffer.Draw> draws) {
		Entities.capture(draws);
	}
}
