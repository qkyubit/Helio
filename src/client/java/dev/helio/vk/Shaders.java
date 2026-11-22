package dev.helio.vk;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.util.shaderc.Shaderc.*;

public final class Shaders {
	private Shaders() {}

	/** GLSL from the jar to SPIR-V 1.4 via the shaderc Minecraft already ships (.vert, .frag; else compute). */
	public static ByteBuffer compile(String name) throws IOException {
		String src;
		try (InputStream in = Shaders.class.getResourceAsStream("/assets/helio/shaders/" + name)) {
			if (in == null) throw new IOException("missing shader " + name);
			src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		long compiler = shaderc_compiler_initialize(), opts = shaderc_compile_options_initialize();
		try {
			shaderc_compile_options_set_target_env(opts, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(opts, shaderc_spirv_version_1_4);
			shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_performance);
			if (dev.helio.Helio.omm) shaderc_compile_options_add_macro_definition(opts, "HELIO_OMM", "1");
			// Source on the heap: the String overload puts it on the thread's MemoryStack, which the shaders outgrew.
			ByteBuffer source = MemoryUtil.memUTF8(src, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
			long result;
			try {
				int kind = name.endsWith(".vert") ? shaderc_vertex_shader : name.endsWith(".frag") ? shaderc_fragment_shader : shaderc_compute_shader;
				result = shaderc_compile_into_spv(compiler, source, kind, file, entry, opts);
			} finally {
				MemoryUtil.memFree(source);
				MemoryUtil.memFree(file);
				MemoryUtil.memFree(entry);
			}
			try {
				if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
					throw new IllegalStateException(name + ": " + shaderc_result_get_error_message(result));
				ByteBuffer bytes = shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				shaderc_result_release(result);
			}
		} finally {
			shaderc_compile_options_release(opts);
			shaderc_compiler_release(compiler);
		}
	}
}
