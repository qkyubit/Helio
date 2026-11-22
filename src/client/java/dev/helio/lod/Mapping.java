package dev.helio.lod;

import dev.helio.Helio;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Helio's ids for block states and biomes in a LOD store: registry ids can change between sessions (mods added or
 * removed), so each store keeps its own list, appended to as states first turn up (states.txt, biomes.txt: one
 * per line, the line number is the id). Id 0 is air. Any thread.
 */
final class Mapping {
	private static final byte PLANT = 1, SOLID = 2;

	private final Object2IntOpenHashMap<BlockState> stateIds = new Object2IntOpenHashMap<>();
	private final List<BlockState> states = new ArrayList<>();
	private byte[] flags = new byte[256];
	private final Object2IntOpenHashMap<String> biomeIds = new Object2IntOpenHashMap<>();
	private final List<String> biomes = new ArrayList<>();
	private final BufferedWriter stateOut, biomeOut;

	Mapping(Path dir, RegistryAccess registries) throws IOException {
		stateIds.defaultReturnValue(-1);
		biomeIds.defaultReturnValue(-1);
		Path stateFile = dir.resolve("states.txt"), biomeFile = dir.resolve("biomes.txt");
		if (Files.exists(stateFile)) {
			for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
				BlockState s = null;
				try {
					s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, line, false).blockState();
				} catch (Exception e) {
					Helio.LOG.warn("Helio LOD: unknown block state {} (a mod removed?): drawn as air", line);
				}
				add(s);
			}
		}
		if (states.isEmpty()) {
			add(Blocks.AIR.defaultBlockState());
			Files.writeString(stateFile, BlockStateParser.serialize(Blocks.AIR.defaultBlockState()) + "\n", StandardCharsets.UTF_8);
		}
		if (Files.exists(biomeFile)) for (String line : Files.readAllLines(biomeFile, StandardCharsets.UTF_8)) addBiome(line);
		stateOut = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		biomeOut = Files.newBufferedWriter(biomeFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private int add(BlockState s) {
		int id = states.size();
		states.add(s);
		if (s != null) stateIds.putIfAbsent(s, id);
		if (id >= flags.length) flags = java.util.Arrays.copyOf(flags, flags.length * 2);
		flags[id] = s == null ? 0 : (byte) ((s.canBeReplaced() && s.getFluidState().isEmpty() && !(s.getBlock() instanceof SnowLayerBlock) ? PLANT : 0)
			| (s.canOcclude() ? SOLID : 0));
		return id;
	}

	private int addBiome(String key) {
		int id = biomes.size();
		biomes.add(key);
		biomeIds.putIfAbsent(key, id);
		return id;
	}

	synchronized int state(BlockState s) {
		if (s.isAir()) return 0;
		int id = stateIds.getInt(s);
		if (id >= 0) return id;
		id = add(s);
		try {
			stateOut.write(BlockStateParser.serialize(s));
			stateOut.newLine();
			stateOut.flush();
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: writing states.txt", e);
		}
		return id;
	}

	synchronized int biome(Holder<Biome> h) {
		String key = h.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
		int id = biomeIds.getInt(key);
		if (id >= 0) return id;
		id = addBiome(key);
		try {
			biomeOut.write(key);
			biomeOut.newLine();
			biomeOut.flush();
		} catch (IOException e) {
			Helio.LOG.warn("Helio LOD: writing biomes.txt", e);
		}
		return id;
	}

	synchronized BlockState stateOf(int id) {
		BlockState s = id < states.size() ? states.get(id) : null;
		return s != null ? s : Blocks.AIR.defaultBlockState();
	}

	synchronized String biomeKey(int id) {
		return id < biomes.size() ? biomes.get(id) : "minecraft:plains";
	}

	synchronized boolean plant(int id) {
		return (flags[id] & PLANT) != 0;
	}

	synchronized boolean solid(int id) {
		return (flags[id] & SOLID) != 0;
	}

	synchronized void close() {
		try {
			stateOut.close();
			biomeOut.close();
		} catch (IOException ignored) {
		}
	}
}
