package dev.helio.mixin;

import dev.helio.rt.Entities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The head of a first-person body another mod draws (First-person Model, which hides the head): its model is
 * submitted a second time for Helio's shadows (LevelRendererMixin), and that copy draws only the head, put back
 * where the mod moved it from (5000 units forward, out of view). Between the model's setupAnim (every mod's included) and its drawing, so it has
 * the same pose; the parts' state is put back right after.
 */
@Mixin(ModelFeatureRenderer.class)
public class ModelFeatureRendererMixin {
	@Unique private static final boolean[] helio$visible = new boolean[6];
	@Unique private static float helio$headZ;
	@Unique private static boolean helio$changed;

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
	private void helio$headOnly(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (!Entities.firstPersonHeadOnly || !(submit.model() instanceof HumanoidModel<?> m)
			|| !((RenderTypeAccessor) submit.renderType()).helio$name().startsWith(Entities.BODY_PREFIX)) return;
		ModelPart[] parts = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
		for (int i = 0; i < parts.length; i++) {
			helio$visible[i] = parts[i].visible;
			parts[i].visible = i == 0;
		}
		helio$headZ = m.head.z;
		m.head.z = m.head.getInitialPose().z();
		helio$changed = true;
	}

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", shift = At.Shift.AFTER))
	private void helio$restore(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (!helio$changed) return;
		helio$changed = false;
		HumanoidModel<?> m = (HumanoidModel<?>) submit.model();
		ModelPart[] parts = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
		for (int i = 0; i < parts.length; i++) parts[i].visible = helio$visible[i];
		m.head.z = helio$headZ;
	}
}
