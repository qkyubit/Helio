package dev.helio.mixin;

import dev.helio.rt.PathTracer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's lightmap colour for full sky light and no block light, as lightmap.fsh works it out: the path tracer
 * divides it out of vanilla's picture past the RT distance to shade that terrain its own way (PathTracer.vanillaSky).
 */
@Mixin(Lightmap.class)
public class LightmapMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void helio$skyColour(LightmapRenderState state, CallbackInfo ci) {
		float nv = state.nightVisionEffectIntensity;
		float r = Math.max(state.ambientColor.x(), state.nightVisionColor.x() * nv) + state.skyLightColor.x() * state.skyFactor;
		float g = Math.max(state.ambientColor.y(), state.nightVisionColor.y() * nv) + state.skyLightColor.y() * state.skyFactor;
		float b = Math.max(state.ambientColor.z(), state.nightVisionColor.z() * nv) + state.skyLightColor.z() * state.skyFactor;
		r = Math.clamp(r - state.darknessEffectScale, 0, 1);
		g = Math.clamp(g - state.darknessEffectScale, 0, 1);
		b = Math.clamp(b - state.darknessEffectScale, 0, 1);
		// notGamma, mixed in by the Brightness setting
		float max = Math.max(r, Math.max(g, b));
		float scaled = max > 0 ? (1 - (float) Math.pow(1 - max, 4)) / max : 1;
		float k = 1 + (scaled - 1) * state.brightness;
		PathTracer.vanillaSky(r * k, g * k, b * k);
	}
}
