package dev.helio.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The server's chunk reads, upgraded by the data fixers (LodImport). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Invoker("readChunk")
	CompletableFuture<Optional<CompoundTag>> helio$readChunk(ChunkPos pos);
}
