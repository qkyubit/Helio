#version 460
// Helio's LODs (M8 step 10c): only where vanilla drew nothing (its depth before this pass is the sky's), and not
// close in, where vanilla's terrain is (a LOD's coarser voxels would bulge past its silhouettes against the sky).
#extension GL_EXT_scalar_block_layout : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// Only tiles reaching into vanilla's render distance need the tests (EDGE): the rest skip them, and keep the
// early depth test a discard would turn off (overdraw shaded in full otherwise).
layout(constant_id = 0) const bool EDGE = true;
layout(binding = 0) uniform sampler2D vanillaDepth;
layout(push_constant, scalar) uniform Push {
	mat4 viewProj;
	uint64_t tiles;
	vec4 sky;
} pc;

layout(location = 0) in flat vec3 colour;
layout(location = 1) in vec3 rel;
layout(location = 0) out vec4 fragColour;

void main() {
	if (EDGE && (texelFetch(vanillaDepth, ivec2(gl_FragCoord.xy), 0).r != 0.0 || dot(rel.xz, rel.xz) < pc.sky.w * pc.sky.w)) discard;
	fragColour = vec4(colour, 1.0);
}
