package dev.helio.mixin;

import dev.helio.rt.PathTracer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While Helio draws the sky, vanilla's distance and weather fog stay off: they faded far terrain into vanilla's
 * pale fog colour, a bright rim against Helio's sky. pathtrace.comp fades it into its own sky instead (edgeFade)
 * and makes its own haze. Fog in water, lava, powder snow, blindness and darkness stays vanilla's.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Shadow
	protected abstract FogType getFogType(Camera camera);

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void helio$noDistanceFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkness, ClientLevel level,
		CallbackInfoReturnable<FogData> cir) {
		if (!PathTracer.drawsSky() || getFogType(camera) != FogType.NONE) return;
		if (camera.entity() instanceof LivingEntity e && (e.hasEffect(MobEffects.BLINDNESS) || e.hasEffect(MobEffects.DARKNESS))) return;
		FogData fog = cir.getReturnValue();
		fog.renderDistanceStart = fog.environmentalStart = 1e6f;
		fog.renderDistanceEnd = fog.environmentalEnd = 1e6f + 1;
	}
}
