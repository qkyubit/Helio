package dev.helio.sodium;

import dev.helio.rt.SodiumMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every quad Sodium meshes, before it's encoded: Helio's copy (SodiumMeshes ignores it outside a meshing task).
@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public class ChunkMeshBufferBuilderMixin {
	@Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"))
	private void helio$copy(ChunkVertexEncoder.Vertex[] vertices, int material, CallbackInfo ci) {
		for (ChunkVertexEncoder.Vertex v : vertices) SodiumMeshes.vertex(v.x, v.y, v.z, v.color, v.u, v.v, v.light);
	}
}
