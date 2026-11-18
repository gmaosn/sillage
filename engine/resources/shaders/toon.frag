#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — soft toon shading: bands of light with gentle transitions, shade tinted blue, light from
// the sky and the ground, a rim of light on silhouettes, cast shadows, haze towards the horizon.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=3) in vec3 sun;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(set = 0, binding = 0) uniform sampler2DShadow shadowMap;
layout(location=0) out vec4 outColor;
#include "shading.glsl"

void main() {
    // No normal: the sky and the interface, in their plain colours.
    if (dot(normal, normal) < 1e-4) { outColor = vec4(toSrgb(color), 1.0); return; }
    vec3 n = normalize(normal);
    vec3 v = normalize(pc.eye.xyz - world);
    float ndl = dot(n, SUN_DIR);
    // Two softened steps: the terminator, then a brighter band facing the sun. Cast shadows take the sun away.
    float shade = sunlight();
    float lit = smoothstep(-0.06, 0.12, ndl) * shade;
    float high = smoothstep(0.62, 0.78, ndl) * shade;
    vec3 light = mix(SHADE * 0.72, SUN, lit) + SUN * high * 0.10;
    // Ambient light: the sky above, the ground below.
    vec3 ambient = mix(GROUND_AMB, SKY_AMB, n.y * 0.5 + 0.5) * 0.22;
    vec3 c = color * (light * 0.86 + ambient);
    // Rim: the edges of shapes brighten a little, mostly on the sun's side. Only on silhouettes: a floor
    // seen at a grazing angle (normal towards the sky) must not turn white.
    float rim = pow(1.0 - max(dot(n, v), 0.0), 4.0) * (1.0 - smoothstep(0.35, 0.8, n.y));
    c += rim * (0.03 + 0.09 * lit) * vec3(1.0, 0.96, 0.88);
    outColor = vec4(toSrgb(hazed(c, world)), 1.0);
}
