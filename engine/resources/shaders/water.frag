#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — water in flat colours: a light lagoon along the shores fading into the open sea, a ring of
// foam that breathes against the beaches, a line of surf rolling in, and short wave crests drifting on
// the open water. The red of the vertices is the distance to the nearest shore, in metres.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=3) in vec3 sun;
// water: the open sea's colour (rgb); shallows: the lagoon's colour (rgb) and how far it reaches (w, m).
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(set = 0, binding = 0) uniform sampler2DShadow shadowMap;
layout(location=0) out vec4 outColor;
#include "shading.glsl"

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}

void main() {
    float t = pc.eye.w;
    float d = color.r;                                   // distance to the shore (m)
    vec2 p = world.xz;
    float reach = max(pc.shallows.w, 0.01);
    float wobble = noise(p * 0.35 + t * 0.05) - 0.5;    // the outlines waver a little
    // The lagoon, then the open sea beyond a soft edge.
    float lagoon = 1.0 - smoothstep(reach * 0.68, reach * 0.74, d + wobble * 0.9);
    vec3 c = mix(pc.water.rgb, pc.shallows.rgb, lagoon * (1.0 - 0.25 * smoothstep(0.0, reach * 0.6, d)));
    vec3 foamColor = vec3(0.93, 0.98, 0.97);
    // Foam against the shore, swelling and ebbing.
    float swell = 0.5 + 0.5 * sin(t * 1.3 + noise(p * 0.5) * 4.0);
    float lace = noise(p * 2.2 + vec2(t * 0.3, -t * 0.2));
    float shore = 1.0 - smoothstep(0.12 + 0.22 * swell, 0.18 + 0.22 * swell, d + wobble * 0.12 + (lace - 0.5) * 0.18);
    // A line of surf rolling towards the beach, broken into pieces, every few seconds.
    float roll = fract(t * 0.16);
    float at = (1.0 - roll) * reach * 0.55 + 0.35;
    float surf = (1.0 - smoothstep(0.04, 0.1, abs(d - at - wobble * 0.3))) * step(0.35, noise(p * 0.8 + 3.0)) * smoothstep(0.0, 0.3, roll) * (1.0 - roll);
    // Short crests on the open water, drifting with the wind.
    vec2 q = p * vec2(0.28, 0.55) + vec2(t * 0.12, 0.0);
    vec2 cell = floor(q); vec2 f = fract(q);
    float h = hash(cell);
    float crest = step(0.55, h) * (1.0 - smoothstep(0.03, 0.07, abs(f.y - 0.5))) * step(abs(f.x - 0.5), 0.18 + 0.12 * h)
        * (0.6 + 0.4 * sin(t * 1.7 + h * 6.28)) * smoothstep(reach * 0.5, reach, d);
    c = mix(c, foamColor, clamp(max(shore, max(surf * 0.85, crest * 0.7)), 0.0, 1.0));
    // The sun on the water, and the shadows of what stands above it.
    float shade = sunlight();
    c *= mix(SHADE * 0.62 + SKY_AMB * 0.25, vec3(1.0), shade) * mix(0.92, 1.0, SUN_Y);
    outColor = vec4(toSrgb(hazed(c, world)), 1.0);
}
