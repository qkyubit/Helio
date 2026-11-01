package dev.helio.rt;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import dev.helio.Helio;
import dev.helio.config.HelioConfig;
import dev.helio.mixin.RenderSetupAccessor;
import dev.helio.mixin.RenderTypeAccessor;
import dev.helio.mixin.StagedDrawAccessor;
import dev.helio.mixin.TextureBindingAccessor;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Per-frame geometry of everything vanilla draws as features in the level: entities, block entities,
 * dropped items, armour, moving blocks. Captured from vanilla's shared vertex staging buffer while the
 * level's features are prepared (not the first-person hand or GUI items, which use their own frames).
 * {@link Scene} turns it into one BLAS per frame; the path tracer shades it and puts its colour over
 * vanilla's own entity pixels (vanilla still draws them, so their depth is there for translucents).
 * <p>
 * Vertices are vanilla's BLOCK layout plus a texture index, {@value #STRIDE} bytes: camera-relative
 * position, colour, UV, light levels, index into {@link #textures}.
 */
public final class Entities {
	public static final int STRIDE = 32, MAX_TEXTURES = 256;

	/** Render types whose geometry is a solid surface. Glints, trims, eyes, shadows, outlines and text are overlays or decals. */
	private static final Set<String> SURFACES = Set.of(
		"entity_solid", "entity_solid_z_offset_forward", "entity_cutout", "entity_cutout_cull", "entity_cutout_z_offset",
		"entity_translucent", "entity_translucent_cull_item_target", "armor_cutout_no_cull",
		"item_cutout", "item_translucent", "solid_moving_block", "cutout_moving_block");
	private static final int FULL_SKY_LIGHT = 240 << 16;

	/** Surface draws of this frame and the texture each samples. */
	private static final Map<StagedVertexBuffer.Draw, Integer> surfaces = new IdentityHashMap<>();
	/** Textures the captured vertices index, this frame. */
	static final List<Identifier> textures = new ArrayList<>();
	private static boolean capturing;
	/** Vertices, 4 per quad. Render thread only. */
	static long vertices;
	private static long capacity;
	static int quads;

	private Entities() {}

	/** Around the level's feature prepare. */
	public static void begin() {
		capturing = Helio.rayTracing && HelioConfig.current.rayTracing;
		surfaces.clear();
		textures.clear();
		quads = 0;
	}

	public static void end() {
		capturing = false;
		surfaces.clear();
	}

	public static void tag(StagedVertexBuffer.Draw draw, RenderType type) {
		if (!capturing || !SURFACES.contains(((RenderTypeAccessor) type).helio$name())) return;
		Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).helio$state()).helio$textures().get("Sampler0");
		if (binding == null) return;
		Identifier texture = ((TextureBindingAccessor) binding).helio$location();
		int index = textures.indexOf(texture);
		if (index < 0) {
			if (textures.size() == MAX_TEXTURES) return; // ponytail: vanilla keeps drawing the rest
			index = textures.size();
			textures.add(texture);
		}
		surfaces.put(draw, index);
	}

	/** Vanilla is about to copy the frame's draws to the GPU and free them. */
	public static void capture(List<StagedVertexBuffer.Draw> draws) {
		if (!capturing) return;
		for (StagedVertexBuffer.Draw draw : draws) {
			Integer texture = surfaces.get(draw);
			if (texture != null) append((StagedDrawAccessor) draw, texture);
		}
	}

	private static void append(StagedDrawAccessor draw, int texture) {
		if (draw.helio$primitiveTopology() != PrimitiveTopology.QUADS) return;
		VertexFormat format = draw.helio$format();
		VertexFormatElement pos = format.getElement("Position"), color = format.getElement("Color"),
			uv = format.getElement("UV0"), light = format.getElement("UV2");
		if (pos == null || pos.format() != GpuFormat.RGB32_FLOAT || uv == null || uv.format() != GpuFormat.RG32_FLOAT) return;
		int stride = format.getVertexSize();
		for (ByteBufferBuilder.Result slice : draw.helio$vertexBufferSlices()) {
			ByteBuffer b = slice.byteBuffer();
			int count = b.remaining() / stride / 4 * 4;
			long needed = ((long) quads * 4 + count) * STRIDE;
			if (needed > capacity) {
				capacity = Math.max(needed, capacity * 2);
				vertices = nmemRealloc(vertices, capacity);
			}
			long src = memAddress(b), dst = vertices + (long) quads * 4 * STRIDE;
			for (int v = 0; v < count; v++, src += stride, dst += STRIDE) {
				memCopy(src + pos.offset(), dst, 12);
				memPutInt(dst + 12, color != null ? memGetInt(src + color.offset()) : -1);
				memCopy(src + uv.offset(), dst + 16, 8);
				memPutInt(dst + 24, light != null ? memGetInt(src + light.offset()) : FULL_SKY_LIGHT);
				memPutInt(dst + 28, texture);
			}
			quads += count / 4;
		}
	}
}
