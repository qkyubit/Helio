package dev.helio.rt;

import dev.helio.config.HelioConfig;
import it.unimi.dsi.fastutil.HashCommon;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Random Block Rotation (setting): natural blocks and still water and lava get each face's texture turned by a random number of quarter
 * turns, picked from the block's position and the face, so wide areas of dirt, sand, stone, leaves or snow stop
 * looking like a grid. The UVs are turned about the sprite's centre as sections compile (Indigo, Sodium and both
 * fluid renderers), so vanilla's raster, Sodium's and the path tracer all show the same faces, partial faces
 * (snow layers, paths) included. Blocks with a direction or a pattern (building blocks, logs, ores, crafting
 * tables, grass block sides, flowing fluids) keep vanilla's, and so do stone and ice: turned, their streaks
 * looked odd (user-checked).
 */
public final class FaceRotation {
	private static final Set<Block> ALL_FACES = Set.of(
		Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.CLAY, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL,
		Blocks.MOSS_BLOCK, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW,
		Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.MAGMA_BLOCK,
		Blocks.END_STONE);
	/** Their sides have a direction (grass hanging over the edge). */
	private static final Set<Block> TOP_ONLY = Set.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.DIRT_PATH,
		Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM);
	private static final Identifier LAVA_STILL = Identifier.withDefaultNamespace("block/lava_still"),
		WATER_STILL = Identifier.withDefaultNamespace("block/water_still");

	private static final class Current {
		BlockState state;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	}
	/** The block being meshed on this compile thread (Sodium; Indigo has its own fields). */
	private static final ThreadLocal<Current> CURRENT = ThreadLocal.withInitial(Current::new);
	/** The fluid being meshed on this compile thread: its still texture, and the quarter turns for it (0: none). */
	private static final class CurrentFluid {
		Identifier still;
		int turns;
	}
	private static final ThreadLocal<CurrentFluid> FLUID = ThreadLocal.withInitial(CurrentFluid::new);

	private static volatile TextureAtlas atlas;
	private static volatile SpriteFinder finder;

	private FaceRotation() {}

	/** Quarter turns (0-3) for this face of this block; 0 where it keeps vanilla's. */
	public static int turns(BlockState state, BlockPos pos, Direction face) {
		if (!HelioConfig.current.randomRotation || face == null || state == null) return 0;
		Block b = state.getBlock();
		if (!ALL_FACES.contains(b) && !state.is(BlockTags.LEAVES) && !(face == Direction.UP && TOP_ONLY.contains(b))) return 0;
		return (int) (HashCommon.mix(Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()) + face.ordinal()) >>> 62);
	}

	/** The block atlas sprite under uv, or null. */
	public static TextureAtlasSprite spriteAt(float u, float v) {
		SpriteFinder f = finder;
		return f == null ? null : f.find(u, v);
	}

	/**
	 * A texture atlas uploaded (render thread): keep the block atlas. Not looked up from compile workers: the
	 * texture manager's getTexture registers a plain texture under a name it doesn't have yet, which crashed meshing.
	 */
	public static void uploaded(TextureAtlas uploaded) {
		if (!uploaded.location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
		finder = ((FabricTextureAtlas) uploaded).spriteFinder();
		atlas = uploaded;
	}

	/** u after turning (u, v) by turns quarter turns about the sprite's centre. */
	public static float u(float u, float v, TextureAtlasSprite sprite, int turns) {
		float s = (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0()), t = (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
		return sprite.getU0() + (turns == 1 ? 1 - t : turns == 2 ? 1 - s : t) * (sprite.getU1() - sprite.getU0());
	}

	/** v after turning (u, v) by turns quarter turns about the sprite's centre. */
	public static float v(float u, float v, TextureAtlasSprite sprite, int turns) {
		float s = (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0()), t = (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
		return sprite.getV0() + (turns == 1 ? s : turns == 2 ? 1 - t : 1 - s) * (sprite.getV1() - sprite.getV0());
	}

	/** Turns a Fabric renderer quad (Indigo) of this block in place. */
	public static void rotate(MutableQuadView quad, BlockState state, BlockPos pos) {
		int turns = turns(state, pos, quad.nominalFace());
		if (turns == 0) return;
		TextureAtlasSprite sprite = spriteAt((quad.u(0) + quad.u(1) + quad.u(2) + quad.u(3)) / 4, (quad.v(0) + quad.v(1) + quad.v(2) + quad.v(3)) / 4);
		if (sprite == null) return;
		for (int i = 0; i < 4; i++) {
			float u = quad.u(i), v = quad.v(i);
			quad.uv(i, u(u, v, sprite, turns), v(u, v, sprite, turns));
		}
	}

	/** Sodium: the block whose quads follow on this thread. */
	public static void block(BlockState state, BlockPos pos) {
		Current c = CURRENT.get();
		c.state = state;
		c.pos.set(pos);
	}

	/** Sodium: turns for a quad of the current block facing this way. */
	public static int currentTurns(Direction face) {
		Current c = CURRENT.get();
		return turns(c.state, c.pos, face);
	}

	/** A fluid is meshed next on this thread: water's and lava's still texture (top and bottom) turns, flowing faces don't. */
	public static void fluid(BlockPos pos, FluidState fluid) {
		CurrentFluid c = FLUID.get();
		c.still = fluid.is(FluidTags.LAVA) ? LAVA_STILL : fluid.is(FluidTags.WATER) ? WATER_STILL : null;
		c.turns = c.still != null && HelioConfig.current.randomRotation
			? (int) (HashCommon.mix(Mth.getSeed(pos.getX(), pos.getY(), pos.getZ())) >>> 62) : 0;
	}

	/** The current fluid's still texture in the block atlas. */
	public static TextureAtlasSprite still() {
		return atlas.getSprite(FLUID.get().still);
	}

	/** Turns for a fluid vertex at (u, v): the current fluid's, if it's on its still texture; else 0. */
	public static int fluidTurns(float u, float v) {
		int turns = FLUID.get().turns;
		if (turns == 0 || atlas == null) return 0;
		// Corners sit on the sprite's edge, where a sprite lookup could pick the neighbour: test the bounds instead.
		// ponytail: a flowing-fluid vertex exactly on the still sprite's edge would turn too (atlas neighbours).
		TextureAtlasSprite still = still();
		return u >= still.getU0() && u <= still.getU1() && v >= still.getV0() && v <= still.getV1() ? turns : 0;
	}
}
