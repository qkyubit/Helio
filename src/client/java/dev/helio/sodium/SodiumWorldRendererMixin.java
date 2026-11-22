package dev.helio.sodium;

import dev.helio.rt.PathTracer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sodium fogs its terrain from its own copy of vanilla's fog data, taken in its own hook on the method where
 * FogRendererMixin switches vanilla's distance fog off, so it may copy before the change. Its chunk fade-in also
 * mixes towards the fog colour: sections fading in at the edge showed pale. Both are switched off here, as they
 * reach Sodium's terrain shader (the fog colour's alpha scales both); its cull distance stays.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class SodiumWorldRendererMixin {
	@ModifyVariable(method = "renderLayer", at = @At("HEAD"), argsOnly = true)
	private FogParameters helio$noDistanceFog(FogParameters fog) {
		if (!PathTracer.noDistanceFog || fog == null) return fog;
		// Alpha 0: the shader mixes towards the fog colour by fog x alpha, and its chunk fade-in (1 - fade) too.
		return new FogParameters(fog.red(), fog.green(), fog.blue(), 0f, 1e6f, 1e6f + 1, 1e6f, 1e6f + 1, fog.cullDistance());
	}
}
