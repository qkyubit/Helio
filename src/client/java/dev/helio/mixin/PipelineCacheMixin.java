package dev.helio.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

@Mixin(PipelineCache.class)
public class PipelineCacheMixin {
	/*
	 * A pipeline missing from the cache is compiled on the background pool while the render thread waits for it.
	 * Section compiles on that pool spin until the render thread uploads their buffers, so when they fill the
	 * pool (Helio has every section in the RT distance compiled, more so on a first launch) neither side moves
	 * and the game freezes. The render thread waits anyway: compile right here.
	 */
	@ModifyArg(method = "get", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"), index = 2)
	private Executor helio$compileOnCaller(Executor executor) {
		return Runnable::run;
	}
}
