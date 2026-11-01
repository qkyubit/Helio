package dev.helio.rt;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.RenderTypeAccessor;
import dev.helio.mixin.StagedDrawAccessor;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Per-frame geometry of everything vanilla draws as features in the level: entities, block entities,
 * dropped items, armour, moving blocks. Captured from vanilla's shared vertex staging buffer while the
 * level's features are prepared (not the first-person hand or GUI items, which use their own frames),
 * as camera-relative positions. {@link Scene} turns it into one BLAS per frame.
 */
public final class Entities {
	/** Render types whose geometry is a solid surface. Glints, trims, eyes, shadows, outlines and text are overlays or decals. */
	private static final Set<String> SURFACES = Set.of(
		"entity_solid", "entity_solid_z_offset_forward", "entity_cutout", "entity_cutout_cull", "entity_cutout_z_offset",
		"entity_translucent", "entity_translucent_cull_item_target", "armor_cutout_no_cull",
		"item_cutout", "item_translucent", "solid_moving_block", "cutout_moving_block");

	private static final Set<StagedVertexBuffer.Draw> surfaces = Collections.newSetFromMap(new IdentityHashMap<>());
	private static boolean capturing;
	/** Camera-relative float3 positions, 4 per quad. Render thread only. */
	static long positions;
	private static long capacity;
	static int quads;

	private Entities() {}

	/** Around the level's feature prepare. */
	public static void begin() {
		capturing = Helio.rayTracing && HelioConfig.current.rayTracing;
		surfaces.clear();
		quads = 0;
	}

	public static void end() {
		capturing = false;
		surfaces.clear();
	}

	public static void tag(StagedVertexBuffer.Draw draw, RenderType type) {
		if (capturing && SURFACES.contains(((RenderTypeAccessor) type).helio$name())) surfaces.add(draw);
	}

	/** Vanilla is about to copy the frame's draws to the GPU and free them. */
	public static void capture(List<StagedVertexBuffer.Draw> draws) {
		if (!capturing) return;
		for (StagedVertexBuffer.Draw draw : draws) if (surfaces.contains(draw)) append((StagedDrawAccessor) draw);
	}

	private static void append(StagedDrawAccessor draw) {
		if (draw.helio$primitiveTopology() != PrimitiveTopology.QUADS) return;
		VertexFormat format = draw.helio$format();
		VertexFormatElement pos = format.getElement("Position");
		if (pos == null || pos.format() != GpuFormat.RGB32_FLOAT) return;
		int stride = format.getVertexSize();
		for (ByteBufferBuilder.Result slice : draw.helio$vertexBufferSlices()) {
			ByteBuffer b = slice.byteBuffer();
			int vertices = b.remaining() / stride / 4 * 4;
			long needed = ((long) quads * 4 + vertices) * 12;
			if (needed > capacity) {
				capacity = Math.max(needed, capacity * 2);
				positions = nmemRealloc(positions, capacity);
			}
			long src = memAddress(b) + pos.offset(), dst = positions + (long) quads * 48;
			for (int v = 0; v < vertices; v++) memCopy(src + (long) v * stride, dst + v * 12L, 12);
			quads += vertices / 4;
		}
	}
}
