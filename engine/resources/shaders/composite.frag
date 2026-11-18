#version 450
// Sillage — the finished image: ink along silhouettes (where the distance jumps), the glow, a soft
// shoulder for what is brighter than white, a gentle vignette, then sRGB with a little dither.
layout(location=0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D scene;   // linear light (rgb), distance to the eye (a)
layout(set = 0, binding = 1) uniform sampler2D bloom;
// texel: 1 / the image's size (xy), the ink's width in pixels (z); params: glow, ink, vignette, saturation.
layout(push_constant) uniform Post { vec4 texel; vec4 params; } pc;
layout(location=0) out vec4 outColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

void main() {
    ivec2 size = textureSize(scene, 0);
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(scene, at, 0);
    vec3 col = c.rgb;
    if (pc.params.y > 0.0) {
        int w = int(pc.texel.z);
        float d = c.a;
        float n = max(max(texelFetch(scene, clamp(at + ivec2(w, 0), ivec2(0), size - 1), 0).a,
                          texelFetch(scene, clamp(at - ivec2(w, 0), ivec2(0), size - 1), 0).a),
                      max(texelFetch(scene, clamp(at + ivec2(0, w), ivec2(0), size - 1), 0).a,
                          texelFetch(scene, clamp(at - ivec2(0, w), ivec2(0), size - 1), 0).a));
        float jump = (n - d) / max(d, 0.5);
        float ink = smoothstep(0.06, 0.2, jump) * (1.0 - smoothstep(35.0, 80.0, d)) * pc.params.y;
        col = mix(col, col * vec3(0.30, 0.30, 0.42), ink);
    }
    col += texture(bloom, uv).rgb * pc.params.x;
    float m = max(col.r, max(col.g, col.b));
    if (m > 0.8) col *= (0.8 + 0.2 * (1.0 - exp(-(m - 0.8) / 0.2))) / m;
    float grey = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = max(mix(vec3(grey), col, pc.params.w), 0.0);
    vec2 q = uv - 0.5;
    col *= 1.0 - pc.params.z * dot(q, q) * 1.6;
    vec3 s = pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2)) + (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    outColor = vec4(s, 1.0);
}
