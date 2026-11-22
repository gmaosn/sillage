#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — the shadow map: the depth of everything that casts a shadow, seen from the main light.
layout(location=0) in vec3 inPosition;
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
layout(location=6) in vec4 inEffect;
layout(location=7) in vec4 inTexture;
layout(location=8) in vec4 inImage;
layout(location=0) out vec2 uv;
layout(location=1) flat out vec2 cut;              // an image's layer and the alpha below which it is cut out (0: opaque)
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
#include "frame.glsl"
#include "light.glsl"
void main() {
    vec4 p = vec4(inPosition, 1.0);
    vec3 world = swayed(vec3(dot(row0, p), dot(row1, p), dot(row2, p)), inEffect.w, pc.eye.w);
    gl_Position = vec4(sunView(world, pc.shadow), 1.0);
    // Only images painted on a model are cut out (mapping 2, see toon.vert and texture.glsl).
    uv = inImage.xy;
    cut = vec2(inTexture.x - 1.0, inTexture.x > 0.5 && inTexture.w > 1.5 ? inImage.z : 0.0);
}
