package dev.helio.mixin;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
public class VulkanConstMixin {
	// ponytail: lets compute passes write vanilla's RGBA8 render targets in place. Applies to every RGBA8
	// render attachment, which may cost framebuffer compression; M3 renders into Helio's own targets instead.
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void helio$addStorage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		if ((usage & 8) != 0 && (format == GpuFormat.RGBA8_UNORM || format == GpuFormat.RG16_FLOAT)) {
			cir.setReturnValue(cir.getReturnValueI() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
		}
	}
}
