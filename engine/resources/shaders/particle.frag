#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — a particle: a round dot with a soft rim. Glowing ones add their light; the others are lit
// like the scene and blended over it (premultiplied; the distance in alpha stays as it was).
layout(location=0) in vec2 corner;
layout(location=1) in vec4 tint;
layout(location=2) in vec3 world;
layout(location=3) in float glows;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec4 outColor;
#include "frame.glsl"
#include "light.glsl"
layout(set = 0, binding = 0) uniform sampler2DArrayShadow shadowMap;
#include "shading.glsl"
void main() {
    float r = length(corner);
    if (r > 1.0) discard;
    if (glows > 0.5) {
        float a = (1.0 - smoothstep(0.0, 1.0, r));
        outColor = vec4(tint.rgb * a * a * tint.a * 2.0, 0.0);
        return;
    }
    float a = (1.0 - r * r) * (1.0 - r * r) * tint.a;      // soft all the way: puffs, not discs
    vec3 light = mix(fr.shade.rgb * 0.72, fr.sunColor.rgb, 0.75) * 0.86 + fr.skyAmb.rgb * 0.3 + pointLights(vec3(0.0), world);
    outColor = vec4(hazed(tint.rgb * light, world) * a, a);
}
