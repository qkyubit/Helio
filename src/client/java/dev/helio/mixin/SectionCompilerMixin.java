package dev.helio.mixin;

import dev.helio.rt.Scene;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
