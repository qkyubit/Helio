// NGX's driver library resolves "the application module" from the address that called into the SDK.
// Called straight from Java FFM, that address is JIT code no file maps, so the driver builds a
// std::string from a null name and aborts. Calling through this file makes the caller libhelio_ngx.so.
#include <stdint.h>
#include <wchar.h>

extern int NVSDK_NGX_VULKAN_Init_with_ProjectID(const char *, int, const char *, const wchar_t *, void *, void *, void *, void *, void *, const void *, int);

// volatile result + noinline stop the compiler turning this into a tail jump, which would hand NGX
// Java's return address again.
__attribute__((visibility("default"), noinline))
int helio_NVSDK_NGX_VULKAN_Init_with_ProjectID(const char *projectId, int engineType, const char *engineVersion,
	const wchar_t *dataPath, void *instance, void *physicalDevice, void *device, void *gipa, void *gdpa,
	const void *featureInfo, int sdkVersion) {
	volatile int r = NVSDK_NGX_VULKAN_Init_with_ProjectID(projectId, engineType, engineVersion, dataPath,
		instance, physicalDevice, device, gipa, gdpa, featureInfo, sdkVersion);
	return r;
}
