#version 460
// Helio's LODs (M8 step 10c): only where vanilla drew nothing (its depth before this pass is the sky's), and not
// close in, where vanilla's terrain is (a LOD's coarser voxels would bulge past its silhouettes against the sky).
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// Only tiles reaching into vanilla's render distance need the tests (EDGE): the rest skip them, and keep the
// early depth test a discard would turn off (overdraw shaded in full otherwise).
layout(constant_id = 0) const bool EDGE = true;
// Water: only where vanilla has nothing at its surface or in front. Vanilla can draw a far section's bed without
// its water (its translucent pass reaches less far): the radius and any-depth tests left a dark line of bed and
// kelp there, at the render distance.
layout(constant_id = 1) const bool WATER = false;
layout(binding = 0) uniform sampler2D vanillaDepth;
layout(push_constant, scalar) uniform Push {
	mat4 viewProj;
	uint64_t tiles;
	vec4 sky;
} pc;

layout(location = 0) in flat vec4 colour;
layout(location = 1) in vec3 rel;
layout(location = 0) out vec4 fragColour;

void main() {
	if (EDGE) {
		float vanilla = texelFetch(vanillaDepth, ivec2(gl_FragCoord.xy), 0).r;
		// Reversed Z, infinite far plane (depth = near / distance): vanilla within 1.5 blocks behind it, or nearer.
		if (WATER ? vanilla >= gl_FragCoord.z * (1.0 - 1.5 / length(rel)) : vanilla != 0.0 || max(length(rel.xz), abs(rel.y)) < pc.sky.w) discard; // vanilla's cylinder
	}
	fragColour = colour;
}
