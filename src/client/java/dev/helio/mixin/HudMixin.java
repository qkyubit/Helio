package dev.helio.mixin;

import dev.helio.ngx.FrameGenSpike;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public class HudMixin {
	/**
	 * No vignette under frame generation: it darkens the whole picture after the HUD-less copy, so the HUD looked
	 * like the entire screen and DLSS-G smeared it (and Helio's HUD pass would put the whole real frame back).
	 */
	@Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
	private void helio$noVignette(GuiGraphicsExtractor graphics, Entity entity, CallbackInfo ci) {
		if (FrameGenSpike.takesOverSwapchain()) ci.cancel();
	}
}
