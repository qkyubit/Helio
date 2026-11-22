package dev.helio.lod;

import dev.helio.Helio;
import dev.helio.mixin.ChunkMapAccessor;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleplayer: the save's chunks into LODs, so a pre-generated world has them without being flown over. Each
 * region file of the dimension, nearest the player first, is read through the server's own chunk storage
 * (ChunkMap.readChunk: its IO worker, with writes still pending, and the data fixers for older chunks), parsed
 * and patched in on POOL's threads. A region is recorded as done (imported.txt) once the worker has its patches
 * mipped and written, so an import resumes where it stopped.
 */
final class LodImport {
	/** Section work (parsing, picking voxels): a few low-priority threads. */
	static final ExecutorService POOL = Executors.newFixedThreadPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3)), r -> {
		Thread t = new Thread(r, "Helio LOD import");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private volatile boolean stopped;
	private final AtomicLong chunks = new AtomicLong();

	void stop() {
		stopped = true;
	}

	static LodImport start(MinecraftServer server, ResourceKey<Level> dimension, Path lodDir) {
		LodImport imp = new LodImport();
		Thread t = new Thread(() -> imp.run(server, dimension, lodDir), "Helio LOD import scan");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
		return imp;
	}

	private void run(MinecraftServer server, ResourceKey<Level> dimension, Path lodDir) {
		try {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) return;
			var id = dimension.identifier();
			Path root = server.getWorldPath(LevelResource.ROOT);
			Path regions = root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath()).resolve("region");
			if (!Files.isDirectory(regions) && dimension == Level.OVERWORLD) regions = root.resolve("region");
			if (!Files.isDirectory(regions)) return;
			Path doneFile = lodDir.resolve("imported.txt");
			Set<String> done = new HashSet<>(Files.exists(doneFile) ? Files.readAllLines(doneFile, StandardCharsets.UTF_8) : List.of());
			List<int[]> todo = new ArrayList<>();
			try (var list = Files.list(regions)) {
				for (Path p : (Iterable<Path>) list::iterator) {
					String[] parts = p.getFileName().toString().split("\\.");
					if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) continue;
					if (done.contains(parts[1] + " " + parts[2])) continue;
					todo.add(new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
				}
			}
			if (todo.isEmpty()) return;
			// Nearest the player first: what's in view comes in first.
			var spawn = level.getRespawnData().pos();
			var player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
			int px = player != null ? player.getBlockX() : spawn.getX(), pz = player != null ? player.getBlockZ() : spawn.getZ();
			todo.sort((a, b) -> Long.compare(dist(a, px, pz), dist(b, px, pz)));
			Helio.LOG.info("Helio LOD: importing {} region files of {}", todo.size(), id);
			var chunkMap = level.getChunkSource().chunkMap;
			PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
			long start = System.nanoTime();
			for (int i = 0; i < todo.size() && !stopped; i++) {
				int rx = todo.get(i)[0], rz = todo.get(i)[1];
				AtomicInteger pending = new AtomicInteger(1);
				Object lock = new Object();
				for (int cz = 0; cz < 32 && !stopped; cz++) for (int cx = 0; cx < 32; cx++) {
					ChunkPos pos = new ChunkPos(rx * 32 + cx, rz * 32 + cz);
					Optional<CompoundTag> tag = ((ChunkMapAccessor) chunkMap).helio$readChunk(pos).join();
					if (tag.isEmpty()) continue;
					pending.incrementAndGet();
					POOL.execute(() -> {
						try {
							importChunk(level, factory, tag.get());
						} catch (Throwable t) {
							Helio.LOG.warn("Helio LOD: chunk {}", pos, t);
						} finally {
							if (pending.decrementAndGet() == 0) synchronized (lock) { lock.notifyAll(); }
						}
					});
				}
				if (pending.decrementAndGet() != 0) synchronized (lock) {
					while (pending.get() != 0 && !stopped) lock.wait(100);
				}
				if (stopped) break;
				String line = rx + " " + rz;
				Lod.afterPatches(() -> {
					try {
						Files.writeString(doneFile, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					} catch (IOException e) {
						Helio.LOG.warn("Helio LOD: writing {}", doneFile, e);
					}
				});
				double s = (System.nanoTime() - start) / 1e9;
				Helio.LOG.info("Helio LOD: imported region {} {} ({}/{}), {} chunks in {}s ({} a second)", rx, rz, i + 1, todo.size(),
					chunks.get(), Math.round(s), Math.round(chunks.get() / Math.max(s, 1e-3)));
			}
		} catch (java.util.concurrent.CancellationException | java.util.concurrent.CompletionException e) {
			if (!stopped && server.isRunning()) Helio.LOG.warn("Helio LOD: import stopped", e); // else the world closed mid-read: resumes next time
		} catch (Throwable t) {
			Helio.LOG.error("Helio LOD: import", t);
		}
	}

	private static long dist(int[] r, int x, int z) {
		long dx = r[0] * 512L + 256 - x, dz = r[1] * 512L + 256 - z;
		return dx * dx + dz * dz;
	}

	private void importChunk(ServerLevel level, PalettedContainerFactory factory, CompoundTag tag) throws InterruptedException {
		SerializableChunkData data = SerializableChunkData.parse(level, factory, tag);
		// Chunks at the generated area's edge are saved part-way (no lighting, no trees yet): vanilla doesn't show
		// them, and their dark air hid their surfaces as stone (Lod.hide).
		if (data.chunkStatus() != ChunkStatus.FULL || !data.lightCorrect()) return;
		ChunkPos pos = data.chunkPos();
		for (SerializableChunkData.SectionData s : data.sectionData()) {
			if (s.chunkSection() == null || s.chunkSection().hasOnlyAir()) continue;
			Lod.submit(Lod.patch(SectionPos.of(pos.x(), s.y(), pos.z()), s.chunkSection().getStates(), Lod.biomes(s.chunkSection().getBiomes()),
				s.skyLight(), s.blockLight(), Lod.mapping));
		}
		chunks.incrementAndGet();
	}
}
