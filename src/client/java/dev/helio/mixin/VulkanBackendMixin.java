package dev.helio.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import dev.helio.ngx.PacedPresenter;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;

@Mixin(VulkanBackend.class)
public class VulkanBackendMixin {
	// One more queue in the graphics family, for frame generation's paced presents (PacedPresenter): the async
	// compute family can't present on every platform (Wayland on NVIDIA), and Mojang uses one queue of this family.
	@WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/MemoryStack;callocFloat(I)Ljava/nio/FloatBuffer;"))
	private static FloatBuffer helio$extraGraphicsQueue(MemoryStack stack, int count, Operation<FloatBuffer> original,
		@Local(argsOnly = true) VulkanPhysicalDevice physicalDevice, @Local Int2IntMap.Entry family) {
		int graphics = physicalDevice.graphicsQueueFamilyAndIndex().leftInt();
		if (family.getIntKey() == graphics) {
			try (MemoryStack s = MemoryStack.stackPush()) {
				IntBuffer n = s.mallocInt(1);
				vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), n, null);
				VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(n.get(0), s);
				vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), n, props);
				if (props.get(graphics).queueCount() > count) {
					PacedPresenter.extraGraphicsQueue = count;
					// Highest priority: its copies are tiny and time-critical, next to Mojang's queue rendering a frame.
					return original.call(stack, count + 1).put(count, 1.0f);
				}
			}
		}
		return original.call(stack, count);
	}
}
