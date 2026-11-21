#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — soft toon shading: bands of light with gentle transitions, shade tinted blue, light from
// the sky and the ground, a rim of light on silhouettes, cast shadows, point lights, the surface's own
// light, its detail texture, haze towards the sky. Writes linear light (rgb) and the distance to the eye (a, for the ink).
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=4) in vec3 glow;
layout(location=5) in vec3 detailAt;
layout(location=6) in vec3 detailNormal;
layout(location=7) flat in vec4 detail;
layout(location=8) in vec2 uv;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(set = 0, binding = 0) uniform sampler2DArrayShadow shadowMap;
layout(location=0) out vec4 outColor;
#include "frame.glsl"
#include "light.glsl"
#include "shading.glsl"
#include "texture.glsl"

void main() {
    float far = length(world - pc.eye.xyz);
    vec4 surface = detailShade(detailAt, detailNormal, detail, uv);
    if (surface.a < detail.w) discard;                     // cut out (leaves, fences: alpha masks)
    // No normal: things in their plain colours.
    if (dot(normal, normal) < 1e-4) { outColor = vec4(color * surface.rgb + glow, far); return; }
    vec3 albedo = color * surface.rgb;
    vec3 n = normalize(normal);
    vec3 v = normalize(pc.eye.xyz - world);
    float ndl = dot(n, fr.sunDir.xyz);
    // Two softened steps: the terminator, then a brighter band facing the light. Cast shadows take it away.
    float shade = sunlight(world, dot(normal, normal) > 1e-8 ? normalize(normal) : vec3(0.0));
    float lit = smoothstep(-0.06, 0.12, ndl) * shade;
    float high = smoothstep(0.62, 0.78, ndl) * shade;
    vec3 light = mix(fr.shade.rgb * 0.72, fr.sunColor.rgb, lit) + fr.sunColor.rgb * high * 0.10;
    // Ambient light: the sky above, the ground below; then the lamps around.
    vec3 ambient = mix(fr.groundAmb.rgb, fr.skyAmb.rgb, n.y * 0.5 + 0.5) * 0.22;
    vec3 c = albedo * (light * 0.86 + ambient + pointLights(n, world));
    // Rim: the edges of shapes brighten a little, mostly on the light's side. Only on silhouettes: a floor
    // seen at a grazing angle (normal towards the sky) must not turn white.
    float rim = pow(1.0 - max(dot(n, v), 0.0), 4.0) * (1.0 - smoothstep(0.35, 0.8, n.y));
    c += rim * (0.03 + 0.09 * lit) * fr.sunColor.rgb * vec3(1.0, 0.96, 0.88);
    outColor = vec4(hazed(c + glow, world), far);
}
