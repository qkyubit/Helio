package dev.helio.vk;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

import java.util.Set;

/**
 * Extra device feature sets handed to Mojang's Vulkan backend as "optional": the game enables each
 * one only if the GPU supports all of it, so an unsupported GPU still boots the vanilla renderer.
 * Feature names are LWJGL struct accessor names (Mojang resolves them reflectively).
 */
public final class HelioFeatures {
	private static final VulkanPNextStruct AS_FEATURES = new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
	private static final VulkanPNextStruct RAY_QUERY_FEATURES = new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class);
	private static final VulkanPNextStruct VK12 = VulkanFeatureSets.VK12_FEATURES_STRUCT;

	public static final FeatureSet RAY_TRACING = new FeatureSet("Helio ray tracing",
		Set.of(
			"VK_KHR_acceleration_structure",
			"VK_KHR_deferred_host_operations",
			"VK_KHR_ray_query",
			"VK_KHR_spirv_1_4" // ray query needs SPIR-V 1.4; Mojang's device is Vulkan 1.2
		),
		Set.of(
			new VulkanFeature(AS_FEATURES, "accelerationStructure"),
			new VulkanFeature(RAY_QUERY_FEATURES, "rayQuery"),
			new VulkanFeature(VK12, "bufferDeviceAddress"),
			new VulkanFeature(VK12, "scalarBlockLayout"),
			new VulkanFeature(VK12, "descriptorIndexing"),
			new VulkanFeature(VK12, "runtimeDescriptorArray"),
			new VulkanFeature(VK12, "descriptorBindingPartiallyBound"),
			new VulkanFeature(VK12, "shaderSampledImageArrayNonUniformIndexing")
		));

	// NVSDK_NGX_VULKAN_RequiredExtensions (SDK 310.9.16) asks for these two plus VK_KHR_push_descriptor
	// (Mojang requires it already) and VK_EXT_buffer_device_address, which RAY_TRACING covers with the
	// core 1.2 bufferDeviceAddress feature (enabling both is invalid). Device must exist before NGX can
	// be queried, so the list is fixed here; the self-test logs NGX's answer to catch drift.
	public static final FeatureSet DLSS = new FeatureSet("Helio DLSS",
		Set.of("VK_NVX_binary_import", "VK_NVX_image_view_handle"), Set.of());

	public static final FeatureSet REFLEX = new FeatureSet("Helio Reflex",
		Set.of("VK_NV_low_latency2", "VK_KHR_present_id"),
		Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class), "presentId")));

	public static final Set<FeatureSet> ALL = Set.of(RAY_TRACING, DLSS, REFLEX);

	private HelioFeatures() {}
}
