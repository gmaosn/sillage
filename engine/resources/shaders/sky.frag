#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — the sky dome: the gradient of the hour, the sun's disc and glow, the moon, the stars at night.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec4 outColor;
#include "frame.glsl"

float hash(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }

void main() {
    vec3 d = normalize(world - pc.eye.xyz);
    vec3 c = skyAt(d);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    // The sun's disc, brighter than white (it blooms), redder when low.
    float s = dot(d, fr.sun.xyz);
    c += mix(vec3(1.0, 0.92, 0.78), vec3(1.0, 0.52, 0.26), low) * 7.0 * smoothstep(0.99950, 0.99972, s) * fr.sun.w * step(-0.02, d.y);
    // The moon: a pale disc, shaded on one side.
    float m = dot(d, fr.moon.xyz);
    if (m > 0.9990) {
        vec3 side = normalize(cross(fr.moon.xyz, vec3(0.0, 1.0, 0.0)));
        float phase = smoothstep(-0.2, 0.6, dot(normalize(d - fr.moon.xyz * m), side) * 0.5 + 0.5);
        c += vec3(0.82, 0.88, 1.0) * 2.2 * smoothstep(0.99968, 0.99980, m) * (0.25 + 0.75 * phase) * fr.moon.w;
    }
    // Stars: a point in some cells of a grid around the eye, twinkling, fading into the haze below.
    float night = fr.sunDir.w;
    if (night > 0.01 && d.y > 0.0) {
        vec3 q = d * 150.0;
        vec3 cell = floor(q);
        float h = hash(cell);
        if (h > 0.982) {
            vec3 at = cell + 0.5 + (vec3(hash(cell + 1.3), hash(cell + 2.7), hash(cell + 4.1)) - 0.5) * 0.5;
            float r = length(q - at);
            float twinkle = 0.65 + 0.35 * sin(pc.eye.w * (1.3 + 3.0 * h) + h * 91.0);
            float big = fract(h * 517.0);
            c += vec3(0.9 + 0.1 * big, 0.92, 1.0) * smoothstep(0.32 + 0.12 * big, 0.0, r) * (0.8 + 1.6 * big) * twinkle
                * night * smoothstep(0.0, 0.25, d.y);
        }
    }
    outColor = vec4(c, 10000.0);
}
