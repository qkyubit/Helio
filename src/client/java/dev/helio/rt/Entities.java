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
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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
 * Vertices are vanilla's BLOCK layout plus a texture index and a motion offset, {@value #STRIDE} bytes:
 * camera-relative position, colour, UV, light levels, index into {@link #textures}, and where the vertex was
 * last frame relative to last frame's camera minus where it is now (NaN if unknown: camera motion only).
 * Vanilla emits the same draws in the same order while the same entities are in view, so a draw with last
 * frame's texture and vertex count is matched vertex for vertex (limbs included).
 */
public final class Entities {
	public static final int STRIDE = 44, MAX_TEXTURES = 256;
	/** Texture-index flag on the first-person body's vertices: camera rays pass through it (pathtrace.comp). */
	private static final int BODY = 1 << 16;
	/**
	 * The player's own body in first person, which vanilla doesn't draw: Helio extracts it anyway and submits it
	 * with render types renamed {@value #BODY_PREFIX}..., which vanilla skips drawing and Helio captures for
	 * shadows and bounce light. Null in third person.
	 */
	public static EntityRenderState firstPersonBody;
	public static final String BODY_PREFIX = "helio_body/";
	private static final Map<RenderType, RenderType> bodyTypes = new IdentityHashMap<>();

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

	private record Span(Identifier texture, int first, int count) {}
	/** This frame's captured draws, and last frame's with their vertex positions (xyz floats). */
	private static List<Span> spans = new ArrayList<>(), lastSpans = new ArrayList<>();
	private static long lastPositions, lastCapacity;

	private Entities() {}

	/** Around the level's feature prepare. */
	public static void begin() {
		capturing = Helio.rayTracing && HelioConfig.current.rayTracing;
		surfaces.clear();
		textures.clear();
		quads = 0;
	}

	public static void end() {
		if (capturing) keepPositions();
		capturing = false;
		surfaces.clear();
	}

	/** This frame's positions become next frame's "last frame". */
	private static void keepPositions() {
		long needed = (long) quads * 4 * 12;
		if (needed > lastCapacity) {
			lastCapacity = Math.max(needed, lastCapacity * 2);
			lastPositions = nmemRealloc(lastPositions, lastCapacity);
		}
		for (int v = 0; v < quads * 4; v++) memCopy(vertices + (long) v * STRIDE, lastPositions + v * 12L, 12);
		List<Span> t = lastSpans;
		lastSpans = spans;
		spans = t;
		spans.clear();
	}

	/**
	 * Whether the path tracer draws surfaces of this render type itself. Vanilla puts blending ones (most mobs use
	 * entity_translucent) in its translucent phase, drawn after the entity composite, so they covered the traced
	 * colour with vanilla's shading; SubmitNodeCollectionMixin keeps them in the solid phase instead.
	 */
	public static boolean traced(RenderType type) {
		return PathTracer.active() && SURFACES.contains(((RenderTypeAccessor) type).helio$name());
	}

	public static void tag(StagedVertexBuffer.Draw draw, RenderType type) {
		String name = ((RenderTypeAccessor) type).helio$name();
		boolean body = name.startsWith(BODY_PREFIX);
		if (!capturing || !SURFACES.contains(body ? name.substring(BODY_PREFIX.length()) : name)) return;
		Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).helio$state()).helio$textures().get("Sampler0");
		if (binding == null) return;
		Identifier texture = ((TextureBindingAccessor) binding).helio$location();
		int index = textures.indexOf(texture);
		if (index < 0) {
			if (textures.size() == MAX_TEXTURES) return; // ponytail: vanilla keeps drawing the rest
			index = textures.size();
			textures.add(texture);
		}
		surfaces.put(draw, body ? index | BODY : index);
	}

	/**
	 * Submits the first-person body: calls carrying a render type go through with its body copy; everything
	 * else (held items, name tag, blob shadow) is dropped. Default methods run on the wrapper, so they end in
	 * those calls too.
	 */
	public static SubmitNodeCollector bodyCollector(SubmitNodeCollector collector) {
		return (SubmitNodeCollector) wrap(collector, SubmitNodeCollector.class);
	}

	private static Object wrap(Object target, Class<?> type) {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
			if (method.getDeclaringClass() == Object.class) return method.invoke(target, args);
			if (method.getReturnType() == OrderedSubmitNodeCollector.class) return wrap(method.invoke(target, args), OrderedSubmitNodeCollector.class);
			boolean model = false;
			for (int i = 0; args != null && i < args.length; i++) {
				if (args[i] instanceof RenderType t) {
					args[i] = bodyTypes.computeIfAbsent(t, k -> RenderType.create(BODY_PREFIX + ((RenderTypeAccessor) k).helio$name(), ((RenderTypeAccessor) k).helio$state()));
					model = true;
				}
			}
			return model || method.getReturnType() != void.class ? method.invoke(target, args) : null;
		});
	}

	/** Vanilla is about to copy the frame's draws to the GPU and free them. */
	public static void capture(List<StagedVertexBuffer.Draw> draws) {
		if (!capturing) return;
		for (StagedVertexBuffer.Draw draw : draws) {
			Integer texture = surfaces.get(draw);
			if (texture == null) continue;
			int first = quads * 4;
			append((StagedDrawAccessor) draw, texture);
			Span span = new Span(textures.get(texture & 0xFFFF), first, quads * 4 - first);
			int i = spans.size();
			spans.add(span);
			Span last = i < lastSpans.size() ? lastSpans.get(i) : null;
			if (last == null || !last.texture.equals(span.texture) || last.count != span.count) continue;
			for (int v = 0; v < span.count; v++) {
				long now = vertices + (long) (first + v) * STRIDE, then = lastPositions + (long) (last.first + v) * 12;
				for (int c = 0; c < 3; c++) memPutFloat(now + 32 + c * 4, memGetFloat(then + c * 4) - memGetFloat(now + c * 4));
			}
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
				memPutFloat(dst + 32, Float.NaN); // motion: filled in by capture() if last frame matches
			}
			quads += count / 4;
		}
	}
}
