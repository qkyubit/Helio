package dev.helio.sodium;

import dev.helio.rt.SodiumMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A section's meshing, on Sodium's compile worker: collect Helio's copy of its quads, hand it over at the end.
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class ChunkBuilderMeshingTaskMixin {
	@Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
		at = @At("HEAD"))
	private void helio$begin(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		SodiumMeshes.begin();
	}

	@Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
		at = @At("RETURN"))
	private void helio$end(ChunkBuildContext context, CancellationToken token, CallbackInfoReturnable<ChunkBuildOutput> cir) {
		ChunkBuildOutput out = cir.getReturnValue();
		SodiumMeshes.end(out == null ? null : ((ChunkBuilderMeshingTask) (Object) this).getRenderSection().getPosition());
	}
}
