package dev.helio.ngx;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Panama FFM bindings to NVIDIA's NGX C API (via libhelio_ngx.so, a relink of the SDK's static loader).
 * Only the calls Helio uses are bound; wrappers mirror the inline helpers in nvsdk_ngx_helpers_vk.h.
 */
public final class Ngx {
	public static final int SUCCESS = 1;

	// Feature IDs (nvsdk_ngx_defs.h)
	public static final int FEATURE_SUPER_SAMPLING = 1;
	public static final int FEATURE_FRAME_GENERATION = 11;
	public static final int FEATURE_RAY_RECONSTRUCTION = 13;

	// Random GUID identifying Helio to NGX (custom engine, no NVIDIA-issued app ID).
	private static final String PROJECT_ID = "5c8e1f0a-3b6d-4f2e-9a71-c4d2e8b0f913";
	private static final int ENGINE_TYPE_CUSTOM = 0;
	private static final int NGX_VERSION_API = 0x15;
	private static final int LOGGING_LEVEL_ON = 1;

	private static final Linker LINKER = Linker.nativeLinker();
	private static SymbolLookup lib;

	private static MethodHandle requiredExtensions, initWithProjectId, getCapabilityParameters, allocateParameters,
		destroyParameters, setUI, setI, setF, setVoidPointer, getI, getUI, createFeature1, evaluateFeature, releaseFeature, shutdown1;

	private Ngx() {}

	/** Extracts and loads libhelio_ngx.so. Must run before any other call. */
	public static void load(Path nativesDir) throws IOException {
		Files.createDirectories(nativesDir);
		Path so = nativesDir.resolve("libhelio_ngx.so");
		try (InputStream in = Ngx.class.getResourceAsStream("/natives/linux-x86_64/libhelio_ngx.so")) {
			if (in == null) throw new IOException("libhelio_ngx.so missing from jar");
			Files.copy(in, so, StandardCopyOption.REPLACE_EXISTING);
		}
		lib = SymbolLookup.libraryLookup(so, Arena.global());

		requiredExtensions = bind("NVSDK_NGX_VULKAN_RequiredExtensions", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
		initWithProjectId = bind("helio_NVSDK_NGX_VULKAN_Init_with_ProjectID", JAVA_INT, // native/ngx_trampoline.c
			ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
		getCapabilityParameters = bind("NVSDK_NGX_VULKAN_GetCapabilityParameters", JAVA_INT, ADDRESS);
		allocateParameters = bind("NVSDK_NGX_VULKAN_AllocateParameters", JAVA_INT, ADDRESS);
		destroyParameters = bind("NVSDK_NGX_VULKAN_DestroyParameters", JAVA_INT, ADDRESS);
		setUI = LINKER.downcallHandle(lib.find("NVSDK_NGX_Parameter_SetUI").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
		setI = LINKER.downcallHandle(lib.find("NVSDK_NGX_Parameter_SetI").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
		setF = LINKER.downcallHandle(lib.find("NVSDK_NGX_Parameter_SetF").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_FLOAT));
		setVoidPointer = LINKER.downcallHandle(lib.find("NVSDK_NGX_Parameter_SetVoidPointer").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
		getI = bind("NVSDK_NGX_Parameter_GetI", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
		getUI = bind("NVSDK_NGX_Parameter_GetUI", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
		createFeature1 = bind("NVSDK_NGX_VULKAN_CreateFeature1", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
		evaluateFeature = bind("NVSDK_NGX_VULKAN_EvaluateFeature_C", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
		releaseFeature = bind("NVSDK_NGX_VULKAN_ReleaseFeature", JAVA_INT, ADDRESS);
		shutdown1 = bind("NVSDK_NGX_VULKAN_Shutdown1", JAVA_INT, ADDRESS);
	}

	private static MethodHandle bind(String name, java.lang.foreign.MemoryLayout ret, java.lang.foreign.MemoryLayout... args) {
		return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("NGX symbol missing: " + name)),
			FunctionDescriptor.of(ret, args));
	}

	/** Instance and device extensions NGX wants, as reported by NGX itself. */
	public static String[][] requiredExtensions() throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment iCount = a.allocate(JAVA_INT), iExts = a.allocate(ADDRESS), dCount = a.allocate(JAVA_INT), dExts = a.allocate(ADDRESS);
			check("RequiredExtensions", (int) requiredExtensions.invokeExact(iCount, iExts, dCount, dExts));
			return new String[][]{strings(iExts, iCount.get(JAVA_INT, 0)), strings(dExts, dCount.get(JAVA_INT, 0))};
		}
	}

	private static String[] strings(MemorySegment pArray, int n) {
		MemorySegment arr = pArray.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * n);
		String[] out = new String[n];
		for (int i = 0; i < n; i++) out[i] = arr.getAtIndex(ADDRESS, i).reinterpret(256).getString(0);
		return out;
	}

	/**
	 * NVSDK_NGX_VULKAN_Init_with_ProjectID. dataDir gets NGX logs; featureDir holds libnvidia-ngx-dlss*.so.
	 * Handles are raw Vulkan pointers (LWJGL .address()).
	 */
	public static void init(long instance, long physicalDevice, long device, long gipa, long gdpa, Path dataDir, Path featureDir) throws Throwable {
		Files.createDirectories(dataDir);
		Arena g = Arena.global(); // NGX keeps these pointers
		MemorySegment path = g.allocateFrom(featureDir.toString(), StandardCharsets.UTF_32LE);
		MemorySegment pathList = g.allocate(ADDRESS);
		pathList.set(ADDRESS, 0, path);

		// NVSDK_NGX_FeatureCommonInfo: {PathListInfo{Path**, uint Length}, Internal*, LoggingInfo{cb, level, bool}}
		// No log callback: NGX dladdr()s it, and an FFM upcall stub lives in no mapped file, so NGX builds a
		// std::string from null and aborts. NGX writes nvngx.log / nvsdk_ngx.log into dataDir instead.
		MemorySegment info = g.allocate(40, 8);
		info.set(ADDRESS, 0, pathList);
		info.set(JAVA_INT, 8, 1);
		info.set(JAVA_INT, 32, LOGGING_LEVEL_ON);

		// ponytail: M0 debugging aid. NGX's fatal errors go to native stderr, which never reaches latest.log;
		// point fd 2 at a file for the duration of init. Remove once init is stable.
		MethodHandle dup = LINKER.downcallHandle(LINKER.defaultLookup().find("dup").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
		MethodHandle dup2 = LINKER.downcallHandle(LINKER.defaultLookup().find("dup2").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
		MethodHandle open = LINKER.downcallHandle(LINKER.defaultLookup().find("open").orElseThrow(),
			FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2));
		int savedErr = (int) dup.invokeExact(2);
		int errFile = (int) open.invokeExact(g.allocateFrom(dataDir.resolve("stderr.log").toString()), 01 | 0100 | 01000 /* WRONLY|CREAT|TRUNC */, 0644);
		int r;
		try {
			int ignored = (int) dup2.invokeExact(errFile, 2);
			r = (int) initWithProjectId.invokeExact(g.allocateFrom(PROJECT_ID), ENGINE_TYPE_CUSTOM, g.allocateFrom("26.3"),
				g.allocateFrom(dataDir.toString(), StandardCharsets.UTF_32LE),
				MemorySegment.ofAddress(instance), MemorySegment.ofAddress(physicalDevice), MemorySegment.ofAddress(device),
				MemorySegment.ofAddress(gipa), MemorySegment.ofAddress(gdpa), info, NGX_VERSION_API);
		} finally {
			int ignored = (int) dup2.invokeExact(savedErr, 2);
		}
		check("Init_with_ProjectID", r);
	}

	public static MemorySegment capabilityParameters() throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(ADDRESS);
			check("GetCapabilityParameters", (int) getCapabilityParameters.invokeExact(out));
			return out.get(ADDRESS, 0);
		}
	}

	public static MemorySegment allocateParameters() throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(ADDRESS);
			check("AllocateParameters", (int) allocateParameters.invokeExact(out));
			return out.get(ADDRESS, 0);
		}
	}

	public static void destroyParameters(MemorySegment params) throws Throwable {
		check("DestroyParameters", (int) destroyParameters.invokeExact(params));
	}

	/** Returns the int parameter, or fallback if NGX doesn't have it. */
	public static int getInt(MemorySegment params, String name, int fallback) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(JAVA_INT);
			int r = (int) getI.invokeExact(params, a.allocateFrom(name), out);
			return r == SUCCESS ? out.get(JAVA_INT, 0) : fallback;
		}
	}

	/** Unsigned-int parameter (NGX stores UI and I separately), or fallback. */
	public static int getUInt(MemorySegment params, String name, int fallback) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(JAVA_INT);
			int r = (int) getUI.invokeExact(params, a.allocateFrom(name), out);
			return r == SUCCESS ? out.get(JAVA_INT, 0) : fallback;
		}
	}

	public static void setFloat(MemorySegment params, String name, float value) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			setF.invokeExact(params, a.allocateFrom(name), value);
		}
	}

	/** NGX copies the pointer, not the pointee: value must stay alive until the evaluate call that reads it. */
	public static void setPointer(MemorySegment params, String name, MemorySegment value) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			setVoidPointer.invokeExact(params, a.allocateFrom(name), value);
		}
	}

	/** Records the feature's work into cmd. */
	public static void evaluate(long cmd, MemorySegment handle, MemorySegment params) throws Throwable {
		check("EvaluateFeature", (int) evaluateFeature.invokeExact(MemorySegment.ofAddress(cmd), handle, params, MemorySegment.NULL));
	}

	/**
	 * Writes an NVSDK_NGX_Resource_VK for an image view into a 56-byte segment:
	 * {ImageView, Image, SubresourceRange{aspect, baseMip, levels, baseLayer, layers}, Format, Width, Height}, Type, ReadWrite.
	 */
	public static MemorySegment imageResource(Arena a, long view, long image, int aspect, int vkFormat, int width, int height, boolean readWrite) {
		MemorySegment r = a.allocate(56, 8);
		r.set(JAVA_LONG, 0, view);
		r.set(JAVA_LONG, 8, image);
		r.set(JAVA_INT, 16, aspect);
		r.set(JAVA_INT, 20, 0);
		r.set(JAVA_INT, 24, 1);
		r.set(JAVA_INT, 28, 0);
		r.set(JAVA_INT, 32, 1);
		r.set(JAVA_INT, 36, vkFormat);
		r.set(JAVA_INT, 40, width);
		r.set(JAVA_INT, 44, height);
		r.set(JAVA_INT, 48, 0); // NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW
		r.set(JAVA_BYTE, 52, (byte) (readWrite ? 1 : 0));
		return r;
	}

	public static void setUInt(MemorySegment params, String name, int value) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			setUI.invokeExact(params, a.allocateFrom(name), value);
		}
	}

	public static void setInt(MemorySegment params, String name, int value) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			setI.invokeExact(params, a.allocateFrom(name), value);
		}
	}

	/** Records feature creation into cmd; caller submits it. Returns the feature handle. */
	public static MemorySegment createFeature(long device, long cmd, int feature, MemorySegment params) throws Throwable {
		try (Arena a = Arena.ofConfined()) {
			MemorySegment out = a.allocate(ADDRESS);
			check("CreateFeature(" + feature + ")", (int) createFeature1.invokeExact(MemorySegment.ofAddress(device), MemorySegment.ofAddress(cmd), feature, params, out));
			return out.get(ADDRESS, 0);
		}
	}

	public static void releaseFeature(MemorySegment handle) throws Throwable {
		check("ReleaseFeature", (int) releaseFeature.invokeExact(handle));
	}

	/** Frees everything NGX holds on the device. All features must be released first; no-op if never loaded. */
	public static void shutdown(long device) throws Throwable {
		if (shutdown1 == null) return;
		check("Shutdown1", (int) shutdown1.invokeExact(MemorySegment.ofAddress(device)));
		shutdown1 = null;
	}

	/** DLSS-SR creation params, as set by NGX_VULKAN_CREATE_DLSS_EXT1. */
	public static void setDlssCreateParams(MemorySegment p, int inW, int inH, int outW, int outH, int perfQuality, int createFlags) throws Throwable {
		setUInt(p, "CreationNodeMask", 1);
		setUInt(p, "VisibilityNodeMask", 1);
		setUInt(p, "Width", inW);
		setUInt(p, "Height", inH);
		setUInt(p, "OutWidth", outW);
		setUInt(p, "OutHeight", outH);
		setInt(p, "PerfQualityValue", perfQuality);
		setInt(p, "DLSS.Feature.Create.Flags", createFlags);
		setInt(p, "DLSS.Enable.Output.Subrects", 0);
	}

	public static void check(String what, int result) {
		if (result != SUCCESS) throw new NgxException(what, result);
	}

	public static final class NgxException extends RuntimeException {
		public final int result;

		NgxException(String what, int result) {
			super("NGX " + what + " failed: 0x" + Integer.toHexString(result));
			this.result = result;
		}
	}
}
