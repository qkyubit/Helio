package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.helio.rt.Entities;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
	// Traced entity surfaces go in the solid phase even when their render type blends: Helio replays that phase
	// before the entity composite, which puts the traced colour back over them (Entities.traced).
	@WrapOperation(method = {"submitModel", "submitBlockModel", "submitCustomGeometry"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderType;hasBlending()Z"))
	private boolean helio$tracedIsSolid(RenderType type, Operation<Boolean> original) {
		return original.call(type) && !Entities.traced(type);
	}
}
