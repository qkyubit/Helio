#version 460
// Helio's LODs (M8 step 10c): each quad from its 16-byte record (LodMesher), pulled by vertex index (4 a quad,
// a shared index buffer makes the two triangles); one indirect draw per tile and facing direction, the tile by
// instance index (LodRenderer).
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

layout(buffer_reference, scalar, buffer_reference_align = 16) readonly buffer Quads { uvec4 q[]; };
// A drawn tile: its corner relative to the camera, voxel size in blocks, its quads.
struct Tile { vec3 origin; float voxel; uint64_t quads; uint slot; uint cmdStart; uint opaqueCount; uint waterCount; uint edge; uint bounds; }; // (lod_cull.comp's)
layout(buffer_reference, scalar, buffer_reference_align = 16) readonly buffer Tiles { Tile t[]; };

layout(push_constant, scalar) uniform Push {
	mat4 viewProj;   // jittered like vanilla's terrain (PathTracer.jitteredProjection), camera-relative
	uint64_t tiles;
	vec4 sky;        // vanilla's lightmap colour at full sky light (as Frame.vanillaSky); w: no LODs nearer than this (blocks)
} pc;

layout(location = 0) out flat vec3 colour;
layout(location = 1) out vec3 rel;

// Per direction (down, up, north, south, west, east): the axis it faces along, and its face's u, v axes.
const ivec3 AXES[6] = ivec3[](ivec3(1, 0, 2), ivec3(1, 0, 2), ivec3(2, 0, 1), ivec3(2, 0, 1), ivec3(0, 2, 1), ivec3(0, 2, 1));
// Vanilla's face shading, so the far composite divides it out as it does for vanilla's terrain.
const float SHADE[6] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6);
const vec2 CORNERS[4] = vec2[](vec2(0, 0), vec2(1, 0), vec2(1, 1), vec2(0, 1));

vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }

void main() {
	Tile t = Tiles(pc.tiles).t[gl_InstanceIndex];
	uint index = uint(gl_VertexIndex);
	uvec4 q = Quads(t.quads).q[index >> 2];
	vec2 corner = CORNERS[index & 3u];
	uint dir = q.y & 7u;
	vec3 p = vec3(q.x & 63u, q.x >> 6 & 63u, q.x >> 12 & 63u);
	vec2 size = vec2(q.x >> 18 & 63u, q.x >> 24 & 63u);
	ivec3 a = AXES[dir];
	p[a.y] += corner.x * size.x;
	p[a.z] += corner.y * size.y;
	rel = t.origin + p * t.voxel;
	gl_Position = pc.viewProj * vec4(rel, 1.0);
	// As vanilla's raster would have it: the texture's colour, face shade, and its lightmap at the face's sky light.
	float sky = float(q.y >> 3 & 15u) / 15.0;
	vec3 c = vec3((q.z >> 16) & 255u, (q.z >> 8) & 255u, q.z & 255u) / 255.0;
	// Water: darker, as vanilla's blend of it over its bed looks (its reflection comes in the composite).
	if (q.w == 1u) c *= 0.55;
	colour = c * SHADE[dir] * pc.sky.rgb * (sky / (4.0 - 3.0 * sky));
}
