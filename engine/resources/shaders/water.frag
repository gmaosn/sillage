#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — water in flat colours: a light lagoon along the shores fading into the open sea, a ring of
// foam that breathes against the beaches, a line of surf rolling in, short wave crests drifting on the
// open water, the sky mirrored at grazing angles, and the sun, the moon and the lamps glittering on small
// waves. The red of the vertices is the distance to the nearest shore, in metres.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=3) in vec3 sun;
// water: the open sea's colour (rgb); shallows: the lagoon's colour (rgb) and how far it reaches (w, m).
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(set = 0, binding = 0) uniform sampler2DShadow shadowMap;
layout(location=0) out vec4 outColor;
#include "frame.glsl"
#include "shading.glsl"

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
// The height of the small waves (no unit: only their slopes matter).
float ripples(vec2 p, float t) { return noise(p * 0.9 + vec2(t * 0.35, t * 0.2)) * 0.6 + noise(p * 2.3 - vec2(t * 0.25, t * 0.45)) * 0.4; }

void main() {
    float t = pc.eye.w;
    float d = color.r;                                   // distance to the shore (m)
    vec2 p = world.xz;
    float far = length(world - pc.eye.xyz);
    float reach = max(pc.shallows.w, 0.01);
    float wobble = noise(p * 0.35 + t * 0.05) - 0.5;    // the outlines waver a little
    // The lagoon, then the open sea beyond a soft edge.
    float lagoon = 1.0 - smoothstep(reach * 0.68, reach * 0.74, d + wobble * 0.9);
    vec3 c = mix(pc.water.rgb, pc.shallows.rgb, lagoon * (1.0 - 0.25 * smoothstep(0.0, reach * 0.6, d)));
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
    float foam = clamp(max(shore, max(surf * 0.85, crest * 0.7)), 0.0, 1.0);
    // The light: the main light (relative to a clear day's sun, so that noon keeps the water's own colours),
    // the sky in the shadows of what stands above it, and the lamps around.
    float shade = sunlight();
    vec3 tint = fr.sunColor.rgb / vec3(1.0, 0.95, 0.84);
    vec3 light = mix(fr.shade.rgb * 0.62 + fr.skyAmb.rgb * 0.25, tint, shade) * mix(0.92, 1.0, max(fr.sunDir.y, 0.0));
    c = mix(c, vec3(0.93, 0.98, 0.97), foam) * (light + pointLights(vec3(0.0, 1.0, 0.0), world));
    // Small waves tilt the surface: the sky mirrored at grazing angles, glitter where they face the lights.
    vec3 v = (pc.eye.xyz - world) / max(far, 1e-4);
    float e = 0.15;
    float h0 = ripples(p, t);
    vec3 n = normalize(vec3((h0 - ripples(p + vec2(e, 0.0), t)) * 2.2, 1.0, (h0 - ripples(p + vec2(0.0, e), t)) * 2.2));
    n = normalize(mix(n, vec3(0.0, 1.0, 0.0), smoothstep(20.0, 120.0, far) * 0.7));
    vec3 r = reflect(-v, n);
    float calm = 1.0 - foam;
    float fresnel = pow(1.0 - max(dot(v, n), 0.0), 4.0);
    c = mix(c, skyAt(vec3(r.x, abs(r.y), r.z)) * (0.55 + 0.45 * shade), fresnel * 0.6 * calm);
    float sparkle = 0.4 + 1.6 * smoothstep(0.45, 0.9, noise(p * 5.0 + vec2(t * 1.1, -t * 0.8)));
    float sunGlint = pow(max(dot(r, fr.sun.xyz), 0.0), 300.0) * fr.sun.w * shade;
    float moonGlint = pow(max(dot(r, fr.moon.xyz), 0.0), 160.0) * fr.moon.w;
    c += (fr.glow.rgb * sunGlint * 6.0 + vec3(0.75, 0.82, 1.0) * moonGlint * 1.6) * sparkle * calm;
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - world;
        float dl = length(l);
        float reachL = fr.lightPos[i].w * 2.5;
        if (dl > reachL) continue;
        float fall = 1.0 - dl / reachL;
        c += fr.lightColor[i].rgb * pow(max(dot(r, l / dl), 0.0), 60.0) * fall * fall * sparkle * 0.8 * calm;
    }
    outColor = vec4(hazed(c, world), far);
}
