package dev.helio.mixin;

import dev.helio.rt.PbrAtlas;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class TextureAtlasMixin {
	@Inject(method = "upload", at = @At("TAIL"))
	private void helio$buildPbr(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		PbrAtlas.rebuild((TextureAtlas) (Object) this);
		dev.helio.rt.FaceRotation.uploaded((TextureAtlas) (Object) this);
	}

	@Inject(method = "cycleAnimationFrames", at = @At("TAIL"))
	private void helio$animatePbr(CallbackInfo ci) {
		PbrAtlas.tick((TextureAtlas) (Object) this);
	}
}
