// Made by tools/shaders_gl.py from engine/resources/shaders: do not edit, run the tool again.
package sillage.gl

/**
 * The shaders in GLSL ES 3.00 (OpenGL ES 3.0, WebGL 2), made from the Vulkan sources so that the two
 * renderers draw with the same code. A test checks that this file is what the tool gives.
 */
internal object GlShaders {
    const val BRIGHT_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec2 v0;
uniform sampler2D source;
layout(std140) uniform Post { vec4 texel; vec4 params; } pc;
layout(location=0) out vec4 outColor;
void main() {
    vec2 o = pc.texel.xy;
    vec3 c = (texture(source, v0 + vec2(-o.x, -o.y)).rgb + texture(source, v0 + vec2(o.x, -o.y)).rgb
            + texture(source, v0 + vec2(-o.x, o.y)).rgb + texture(source, v0 + vec2(o.x, o.y)).rgb) * 0.25;
    float threshold = pc.params.x, knee = 0.5;
    float b = max(c.r, max(c.g, c.b));
    float soft = clamp(b - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    outColor = vec4(c * max(soft, b - threshold) / max(b, 1e-4), 1.0);
}
"""

    const val COMPOSITE_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec2 v0;
uniform sampler2D scene;
uniform sampler2D bloom;
layout(std140) uniform Post { vec4 texel; vec4 params; } pc;
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
    col += texture(bloom, v0).rgb * pc.params.x;
    float m = max(col.r, max(col.g, col.b));
    if (m > 0.8) col *= (0.8 + 0.2 * (1.0 - exp(-(m - 0.8) / 0.2))) / m;
    float grey = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = max(mix(vec3(grey), col, pc.params.w), 0.0);
    vec2 q = v0 - 0.5;
    col *= 1.0 - pc.params.z * dot(q, q) * 1.6;
    vec3 s = pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2)) + (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    outColor = vec4(s, 1.0);
}
"""

    const val DOWN_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec2 v0;
uniform sampler2D source;
layout(std140) uniform Post { vec4 texel; vec4 params; } pc;
layout(location=0) out vec4 outColor;
void main() {
    vec2 o = pc.texel.xy;
    vec3 c = texture(source, v0).rgb * 4.0
           + texture(source, v0 + vec2(-o.x, -o.y)).rgb + texture(source, v0 + vec2(o.x, -o.y)).rgb
           + texture(source, v0 + vec2(-o.x, o.y)).rgb + texture(source, v0 + vec2(o.x, o.y)).rgb;
    outColor = vec4(c / 8.0, 1.0);
}
"""

    const val HUD_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec3 v0;
in float v1;
in vec2 v2;
flat in vec4 v3;
flat in vec4 v4;
uniform sampler2D atlas;
layout(std140) uniform Push { vec4 size; } pc;
layout(location=0) out vec4 outColor;
float glyph(vec2 t) { return texture(atlas, t * pc.size.zw).r; }
void main() {
    float a = v1;
    int kind = int(v3.x + 0.5);
    if (kind == 1) {
        float c = glyph(v2);
        float r = min(v3.y, 4.0);
        if (r > 0.0) {
            for (int i = 0; i < 16; i++) {
                float t = float(i) * 0.39269908;
                vec2 d = vec2(cos(t), sin(t));
                c = max(c, glyph(v2 + d * r));
                c = max(c, glyph(v2 + d * r * 0.5));
            }
        }
        a *= c;
    } else if (kind >= 2) {
        vec2 q = abs(v2) - v3.zw + vec2(v3.y);
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - v3.y;
        float soft = max(v4.x, 0.5);
        float inside = 1.0 - smoothstep(-0.5 * soft, 0.5 * soft, d);
        if (kind == 3) inside *= smoothstep(-0.5 * soft, 0.5 * soft, d + v4.y);
        a *= inside;
    }
    outColor = vec4(pow(clamp(v0, 0.0, 1.0), vec3(1.0 / 2.2)), a);
}
"""

    const val HUD_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
layout(location=0) in vec2 inPosition;
layout(location=1) in vec2 inUv;
layout(location=2) in vec4 inColor;
layout(location=3) in vec4 inShape;
layout(location=4) in vec4 inExtra;
out vec3 v0;
out float v1;
out vec2 v2;
flat out vec4 v3;
flat out vec4 v4;
void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    v0 = inColor.rgb;
    v1 = 1.0 - inColor.a;
    v2 = inUv;
    v3 = inShape;
    v4 = inExtra;
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val LINES_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec4 v0;
layout(location=0) out vec4 outColor;
void main() { outColor = v0; }
"""

    const val LINES_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
layout(location=0) in vec3 inPosition;
layout(location=1) in vec4 inColor;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
out vec4 v0;
void main() {
    gl_Position = pc.viewProj * vec4(inPosition, 1.0);
    v0 = inColor;
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val LINES_XRAY_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec4 v0;
layout(location=0) out vec4 outColor;
void main() { outColor = vec4(v0.rgb, v0.a * 0.28); }
"""

    const val OMBRE_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec3 v0;
in vec3 v1;
in vec3 v2;
layout(location=0) out vec4 outColor;
void main() {
    float a = v1.r;
    outColor = vec4(0.10, 0.12, 0.22, 0.38 * a * a);
}
"""

    const val PARTICLE_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec2 v0;
in vec4 v1;
in vec3 v2;
in float v3;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec4 outColor;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const float SHADOW_DEPTH = 80.0;
vec3 sunView(vec3 v2, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = v2 - shadow.xyz;
    float depth = max(SHADOW_DEPTH, shadow.w * 1.5);
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * depth));
}
uniform sampler2DArrayShadow shadowMap;
float cascadeLight(int c, vec3 v2, vec3 n) {
    vec4 sq = fr.cascade[c];
    vec3 s = sunView(v2 + n * (sq.w / 600.0), sq);
    vec2 uv = s.xy * 0.5 + 0.5;
    float texel = 1.25 / fr.groundAmb.w;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec4(uv + vec2(i, j) * texel, float(c), s.z));
    return sum / 9.0;
}
float sunlight(vec3 v2, vec3 n) {
    if (pc.shadow.w <= 0.0) return 1.0;
    for (int c = 0; c < 3; c++) {
        vec4 sq = fr.cascade[c];
        if (sq.w <= 0.0) break;
        vec3 s = sunView(v2, sq);
        float edge = max(abs(s.x), abs(s.y));
        if (edge >= 1.0) continue;
        float here = cascadeLight(c, v2, n);
        float blend = smoothstep(0.8, 1.0, edge);
        if (blend <= 0.0) return here;
        bool last = c == 2 || fr.cascade[c + 1].w <= 0.0;
        return mix(here, last ? 1.0 : cascadeLight(c + 1, v2, n), blend);
    }
    return 1.0;
}
vec3 pointLights(vec3 n, vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - p;
        float r = fr.lightPos[i].w;
        float d2 = dot(l, l);
        if (d2 >= r * r) continue;
        float d = sqrt(d2);
        float x2 = d2 / (r * r);
        float fall = (1.0 - x2) * (1.0 - x2) / (1.0 + 12.0 * x2);
        float ndl = n == vec3(0.0) ? 1.0 : dot(n, l / max(d, 1e-4));
        sum += fr.lightColor[i].rgb * fall * (0.25 + 0.75 * smoothstep(-0.1, 0.3, ndl));
    }
    return sum;
}
vec3 hazed(vec3 c, vec3 v2) {
    vec3 d = v2 - pc.eye.xyz;
    float far = length(d);
    vec3 dir = d / max(far, 1e-4);
    return mix(c, skyAt(vec3(dir.x, max(dir.y, 0.0) * 0.3, dir.z)), smoothstep(fr.sunColor.w, fr.shade.w, far));
}
void main() {
    float r = length(v0);
    if (r > 1.0) discard;
    if (v3 > 0.5) {
        float a = (1.0 - smoothstep(0.0, 1.0, r));
        outColor = vec4(v1.rgb * a * a * v1.a * 2.0, 0.0);
        return;
    }
    float a = (1.0 - r * r) * (1.0 - r * r) * v1.a;
    vec3 light = mix(fr.shade.rgb * 0.72, fr.sunColor.rgb, 0.75) * 0.86 + fr.skyAmb.rgb * 0.3 + pointLights(vec3(0.0), v2);
    outColor = vec4(hazed(v1.rgb * light, v2) * a, a);
}
"""

    const val PARTICLE_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
layout(location=0) in vec4 inPlace;
layout(location=1) in vec4 inColor;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
out vec2 v0;
out vec4 v1;
out vec3 v2;
out float v3;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));
void main() {
    v0 = CORNERS[gl_VertexID];
    v2 = inPlace.xyz + (fr.camRight.xyz * v0.x + fr.camUp.xyz * v0.y) * abs(inPlace.w);
    gl_Position = pc.viewProj * vec4(v2, 1.0);
    v1 = inColor;
    v3 = inPlace.w < 0.0 ? 1.0 : 0.0;
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val POST_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
out vec2 v0;
void main() {
    v0 = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v0 * 2.0 - 1.0, 0.0, 1.0);
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val SHADOW_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
uniform sampler2DArray detailMaps;
in vec2 v0;
flat in vec2 v1;
void main() {
    if (v1.y > 0.0 && texture(detailMaps, vec3(v0, v1.x)).a < v1.y) discard;
}
"""

    const val SHADOW_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
layout(location=0) in vec3 inPosition;
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
layout(location=6) in vec4 inEffect;
layout(location=7) in vec4 inTexture;
layout(location=8) in vec4 inImage;
out vec2 v0;
flat out vec2 v1;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 world, float sway, float t) {
    if (sway <= 0.0) return world;
    float phase = dot(world.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return world + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const float SHADOW_DEPTH = 80.0;
vec3 sunView(vec3 world, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = world - shadow.xyz;
    float depth = max(SHADOW_DEPTH, shadow.w * 1.5);
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * depth));
}
void main() {
    vec4 p = vec4(inPosition, 1.0);
    vec3 world = swayed(vec3(dot(row0, p), dot(row1, p), dot(row2, p)), inEffect.w, pc.eye.w);
    gl_Position = vec4(sunView(world, pc.shadow), 1.0);
    v0 = inImage.xy;
    v1 = vec2(inTexture.x - 1.0, inTexture.x > 0.5 && inTexture.w > 1.5 ? inImage.z : 0.0);
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val SKY_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec3 v0;
in vec3 v1;
in vec3 v2;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec4 outColor;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
float hash(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
void main() {
    vec3 d = normalize(v2 - pc.eye.xyz);
    vec3 c = skyAt(d);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float s = dot(d, fr.sun.xyz);
    c += mix(vec3(1.0, 0.92, 0.78), vec3(1.0, 0.52, 0.26), low) * 7.0 * smoothstep(0.99950, 0.99972, s) * fr.sun.w * step(-0.02, d.y);
    float m = dot(d, fr.moon.xyz);
    if (m > 0.9990) {
        vec3 side = normalize(cross(fr.moon.xyz, vec3(0.0, 1.0, 0.0)));
        float phase = smoothstep(-0.2, 0.6, dot(normalize(d - fr.moon.xyz * m), side) * 0.5 + 0.5);
        c += vec3(0.82, 0.88, 1.0) * 2.2 * smoothstep(0.99968, 0.99980, m) * (0.25 + 0.75 * phase) * fr.moon.w;
    }
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
"""

    const val TOON_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec3 v0;
in vec3 v1;
in vec3 v2;
in vec3 v4;
in vec3 v5;
in vec3 v6;
flat in vec4 v7;
in vec2 v8;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
uniform sampler2DArrayShadow shadowMap;
layout(location=0) out vec4 outColor;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const float SHADOW_DEPTH = 80.0;
vec3 sunView(vec3 v2, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = v2 - shadow.xyz;
    float depth = max(SHADOW_DEPTH, shadow.w * 1.5);
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * depth));
}
float cascadeLight(int c, vec3 v2, vec3 n) {
    vec4 sq = fr.cascade[c];
    vec3 s = sunView(v2 + n * (sq.w / 600.0), sq);
    vec2 v8 = s.xy * 0.5 + 0.5;
    float texel = 1.25 / fr.groundAmb.w;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec4(v8 + vec2(i, j) * texel, float(c), s.z));
    return sum / 9.0;
}
float sunlight(vec3 v2, vec3 n) {
    if (pc.shadow.w <= 0.0) return 1.0;
    for (int c = 0; c < 3; c++) {
        vec4 sq = fr.cascade[c];
        if (sq.w <= 0.0) break;
        vec3 s = sunView(v2, sq);
        float edge = max(abs(s.x), abs(s.y));
        if (edge >= 1.0) continue;
        float here = cascadeLight(c, v2, n);
        float blend = smoothstep(0.8, 1.0, edge);
        if (blend <= 0.0) return here;
        bool last = c == 2 || fr.cascade[c + 1].w <= 0.0;
        return mix(here, last ? 1.0 : cascadeLight(c + 1, v2, n), blend);
    }
    return 1.0;
}
vec3 pointLights(vec3 n, vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - p;
        float r = fr.lightPos[i].w;
        float d2 = dot(l, l);
        if (d2 >= r * r) continue;
        float d = sqrt(d2);
        float x2 = d2 / (r * r);
        float fall = (1.0 - x2) * (1.0 - x2) / (1.0 + 12.0 * x2);
        float ndl = n == vec3(0.0) ? 1.0 : dot(n, l / max(d, 1e-4));
        sum += fr.lightColor[i].rgb * fall * (0.25 + 0.75 * smoothstep(-0.1, 0.3, ndl));
    }
    return sum;
}
vec3 hazed(vec3 c, vec3 v2) {
    vec3 d = v2 - pc.eye.xyz;
    float far = length(d);
    vec3 dir = d / max(far, 1e-4);
    return mix(c, skyAt(vec3(dir.x, max(dir.y, 0.0) * 0.3, dir.z)), smoothstep(fr.sunColor.w, fr.shade.w, far));
}
uniform sampler2DArray detailMaps;
vec3 linearFromSrgb(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec4 detailShade(vec3 p, vec3 n, vec4 d, vec2 v8) {
    if (d.y <= 0.0) return vec4(1.0);
    if (d.z > 1.5) { vec4 t = texture(detailMaps, vec3(v8, d.x)); return vec4(linearFromSrgb(t.rgb), t.a); }
    vec3 t;
    if (d.z > 0.5) {
        vec3 f = cross(dFdx(p), dFdy(p));
        f = dot(f, f) > 1e-20 ? normalize(f) : vec3(0.0, 1.0, 0.0);
        vec2 v8;
        if (abs(f.y) > 0.97) v8 = p.xz;
        else {
            vec3 u = normalize(cross(vec3(0.0, 1.0, 0.0), f));
            vec3 v = cross(f, u);
            v8 = vec2(dot(p, u), dot(p, v));
        }
        t = texture(detailMaps, vec3(v8, d.x)).rgb;
    } else {
        vec3 dx = dFdx(p), dy = dFdy(p);
        vec3 w = pow(abs(n), vec3(4.0));
        w /= max(w.x + w.y + w.z, 1e-5);
        t = vec3(0.0);
        if (w.x > 0.02) t += textureGrad(detailMaps, vec3(p.zy, d.x), dx.zy, dy.zy).rgb * w.x;
        if (w.y > 0.02) t += textureGrad(detailMaps, vec3(p.xz, d.x), dx.xz, dy.xz).rgb * w.y;
        if (w.z > 0.02) t += textureGrad(detailMaps, vec3(p.xy, d.x), dx.xy, dy.xy).rgb * w.z;
        t /= max((w.x > 0.02 ? w.x : 0.0) + (w.y > 0.02 ? w.y : 0.0) + (w.z > 0.02 ? w.z : 0.0), 1e-5);
    }
    return vec4(mix(vec3(1.0), t * 2.0, d.y), 1.0);
}
void main() {
    float far = length(v2 - pc.eye.xyz);
    vec4 surface = detailShade(v5, v6, v7, v8);
    if (surface.a < v7.w) discard;
    if (dot(v0, v0) < 1e-4) { outColor = vec4(v1 * surface.rgb + v4, far); return; }
    vec3 albedo = v1 * surface.rgb;
    vec3 n = normalize(v0);
    vec3 v = normalize(pc.eye.xyz - v2);
    float ndl = dot(n, fr.sunDir.xyz);
    float shade = sunlight(v2, dot(v0, v0) > 1e-8 ? normalize(v0) : vec3(0.0));
    float soft = fr.zenith.w;
    float lit = mix(smoothstep(-0.06, 0.12, ndl), clamp(ndl * 0.9 + 0.1, 0.0, 1.0), soft) * shade;
    float high = smoothstep(0.62, 0.78, ndl) * shade * (1.0 - soft);
    vec3 light = mix(fr.shade.rgb * 0.72, fr.sunColor.rgb, lit) + fr.sunColor.rgb * high * 0.10;
    vec3 ambient = mix(fr.groundAmb.rgb, fr.skyAmb.rgb, n.y * 0.5 + 0.5) * (0.22 + 0.55 * soft);
    vec3 c = albedo * (light * 0.86 + ambient + pointLights(n, v2));
    float rim = pow(1.0 - max(dot(n, v), 0.0), 4.0) * (1.0 - smoothstep(0.35, 0.8, n.y));
    c += rim * (1.0 - 0.75 * soft) * (0.03 + 0.09 * lit) * fr.sunColor.rgb * vec3(1.0, 0.96, 0.88);
    float sheen = pow(max(dot(n, normalize(fr.sunDir.xyz + v)), 0.0), 26.0);
    c += albedo * fr.sunColor.rgb * (sheen * 0.35 * soft * lit);
    outColor = vec4(hazed(c + v4, v2), far);
}
"""

    const val TOON_VERT = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
layout(location=6) in vec4 inEffect;
layout(location=7) in vec4 inTexture;
layout(location=8) in vec4 inImage;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
out vec3 v0;
out vec3 v1;
out vec3 v2;
out vec3 v4;
out vec3 v5;
out vec3 v6;
flat out vec4 v7;
out vec2 v8;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const float SHADOW_DEPTH = 80.0;
vec3 sunView(vec3 v2, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = v2 - shadow.xyz;
    float depth = max(SHADOW_DEPTH, shadow.w * 1.5);
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * depth));
}
void main() {
    vec4 p = vec4(inPosition, 1.0);
    v2 = swayed(vec3(dot(row0, p), dot(row1, p), dot(row2, p)), inEffect.w, pc.eye.w);
    gl_Position = pc.viewProj * vec4(v2, 1.0);
    v0 = vec3(dot(row0.xyz, inNormal), dot(row1.xyz, inNormal), dot(row2.xyz, inNormal));
    v1 = inColor;
    float scale = length(row0.xyz);
    v5 = inPosition * scale / max(inTexture.y, 1e-3);
    v6 = inNormal;
    v7 = vec4(inTexture.x - 1.0, inTexture.x > 0.5 ? inTexture.z : 0.0, inTexture.w, inImage.z);
    v8 = inImage.xy;
    v4 = any(lessThan(inEffect.rgb, vec3(0.0))) ? -inEffect.rgb * fr.sunDir.w : inEffect.rgb;
    gl_Position.z = 2.0 * gl_Position.z - gl_Position.w;
}
"""

    const val UP_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec2 v0;
uniform sampler2D smaller;
uniform sampler2D level;
layout(std140) uniform Post { vec4 texel; vec4 params; } pc;
layout(location=0) out vec4 outColor;
void main() {
    vec2 o = pc.texel.xy;
    vec3 c = texture(smaller, v0 + vec2(-o.x, 0.0)).rgb + texture(smaller, v0 + vec2(o.x, 0.0)).rgb
           + texture(smaller, v0 + vec2(0.0, -o.y)).rgb + texture(smaller, v0 + vec2(0.0, o.y)).rgb
           + (texture(smaller, v0 + vec2(-o.x, -o.y) * 0.5).rgb + texture(smaller, v0 + vec2(o.x, -o.y) * 0.5).rgb
            + texture(smaller, v0 + vec2(-o.x, o.y) * 0.5).rgb + texture(smaller, v0 + vec2(o.x, o.y) * 0.5).rgb) * 2.0;
    outColor = vec4(c / 12.0 + texture(level, v0).rgb, 1.0);
}
"""

    const val WATER_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler2DArray;
precision highp sampler2DArrayShadow;
in vec3 v0;
in vec3 v1;
in vec3 v2;
layout(std140) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
uniform sampler2DArrayShadow shadowMap;
layout(location=0) out vec4 outColor;
#define MAX_LIGHTS 16
layout(std140) uniform Frame {
    vec4 sunDir;
    vec4 sunColor;
    vec4 shade;
    vec4 skyAmb;
    vec4 groundAmb;
    vec4 horizon;
    vec4 middle;
    vec4 zenith;
    vec4 sun;
    vec4 moon;
    vec4 wind;
    vec4 camRight;
    vec4 camUp;
    vec4 glow;
    vec4 cascade[3];
    vec4 lightPos[MAX_LIGHTS];
    vec4 lightColor[MAX_LIGHTS];
} fr;
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}
vec3 swayed(vec3 v2, float sway, float t) {
    if (sway <= 0.0) return v2;
    float phase = dot(v2.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return v2 + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
const float SHADOW_DEPTH = 80.0;
vec3 sunView(vec3 v2, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = v2 - shadow.xyz;
    float depth = max(SHADOW_DEPTH, shadow.w * 1.5);
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * depth));
}
float cascadeLight(int c, vec3 v2, vec3 n) {
    vec4 sq = fr.cascade[c];
    vec3 s = sunView(v2 + n * (sq.w / 600.0), sq);
    vec2 uv = s.xy * 0.5 + 0.5;
    float texel = 1.25 / fr.groundAmb.w;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec4(uv + vec2(i, j) * texel, float(c), s.z));
    return sum / 9.0;
}
float sunlight(vec3 v2, vec3 n) {
    if (pc.shadow.w <= 0.0) return 1.0;
    for (int c = 0; c < 3; c++) {
        vec4 sq = fr.cascade[c];
        if (sq.w <= 0.0) break;
        vec3 s = sunView(v2, sq);
        float edge = max(abs(s.x), abs(s.y));
        if (edge >= 1.0) continue;
        float here = cascadeLight(c, v2, n);
        float blend = smoothstep(0.8, 1.0, edge);
        if (blend <= 0.0) return here;
        bool last = c == 2 || fr.cascade[c + 1].w <= 0.0;
        return mix(here, last ? 1.0 : cascadeLight(c + 1, v2, n), blend);
    }
    return 1.0;
}
vec3 pointLights(vec3 n, vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - p;
        float r = fr.lightPos[i].w;
        float d2 = dot(l, l);
        if (d2 >= r * r) continue;
        float d = sqrt(d2);
        float x2 = d2 / (r * r);
        float fall = (1.0 - x2) * (1.0 - x2) / (1.0 + 12.0 * x2);
        float ndl = n == vec3(0.0) ? 1.0 : dot(n, l / max(d, 1e-4));
        sum += fr.lightColor[i].rgb * fall * (0.25 + 0.75 * smoothstep(-0.1, 0.3, ndl));
    }
    return sum;
}
vec3 hazed(vec3 c, vec3 v2) {
    vec3 d = v2 - pc.eye.xyz;
    float far = length(d);
    vec3 dir = d / max(far, 1e-4);
    return mix(c, skyAt(vec3(dir.x, max(dir.y, 0.0) * 0.3, dir.z)), smoothstep(fr.sunColor.w, fr.shade.w, far));
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
float ripples(vec2 p, float t) { return noise(p * 0.9 + vec2(t * 0.35, t * 0.2)) * 0.6 + noise(p * 2.3 - vec2(t * 0.25, t * 0.45)) * 0.4; }
void main() {
    float t = pc.eye.w;
    float d = v1.r;
    vec2 p = v2.xz;
    float far = length(v2 - pc.eye.xyz);
    float reach = max(pc.shallows.w, 0.01);
    float wobble = noise(p * 0.35 + t * 0.05) - 0.5;
    float lagoon = 1.0 - smoothstep(reach * 0.68, reach * 0.74, d + wobble * 0.9);
    vec3 c = mix(pc.water.rgb, pc.shallows.rgb, lagoon * (1.0 - 0.25 * smoothstep(0.0, reach * 0.6, d)));
    float swell = 0.5 + 0.5 * sin(t * 1.3 + noise(p * 0.5) * 4.0);
    float lace = noise(p * 2.2 + vec2(t * 0.3, -t * 0.2));
    float shore = 1.0 - smoothstep(0.12 + 0.22 * swell, 0.18 + 0.22 * swell, d + wobble * 0.12 + (lace - 0.5) * 0.18);
    float roll = fract(t * 0.16);
    float at = (1.0 - roll) * reach * 0.55 + 0.35;
    float surf = (1.0 - smoothstep(0.04, 0.1, abs(d - at - wobble * 0.3))) * step(0.35, noise(p * 0.8 + 3.0)) * smoothstep(0.0, 0.3, roll) * (1.0 - roll);
    vec2 q = p * vec2(0.28, 0.55) + vec2(t * 0.12, 0.0);
    vec2 cell = floor(q); vec2 f = fract(q);
    float h = hash(cell);
    float crest = step(0.55, h) * (1.0 - smoothstep(0.03, 0.07, abs(f.y - 0.5))) * step(abs(f.x - 0.5), 0.18 + 0.12 * h)
        * (0.6 + 0.4 * sin(t * 1.7 + h * 6.28)) * smoothstep(reach * 0.5, reach, d);
    float foam = clamp(max(shore, max(surf * 0.85, crest * 0.7)), 0.0, 1.0);
    float shade = sunlight(v2, dot(v0, v0) > 1e-8 ? normalize(v0) : vec3(0.0));
    vec3 tint = fr.sunColor.rgb / vec3(1.0, 0.95, 0.84);
    vec3 light = mix(fr.shade.rgb * 0.62 + fr.skyAmb.rgb * 0.25, tint, shade) * mix(0.92, 1.0, max(fr.sunDir.y, 0.0));
    c = mix(c, vec3(0.93, 0.98, 0.97), foam) * (light + pointLights(vec3(0.0, 1.0, 0.0), v2));
    vec3 v = (pc.eye.xyz - v2) / max(far, 1e-4);
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
        vec3 l = fr.lightPos[i].xyz - v2;
        float dl = length(l);
        float reachL = fr.lightPos[i].w * 2.5;
        if (dl > reachL) continue;
        float fall = 1.0 - dl / reachL;
        c += fr.lightColor[i].rgb * pow(max(dot(r, l / dl), 0.0), 60.0) * fall * fall * sparkle * 0.8 * calm;
    }
    outColor = vec4(hazed(c, v2), far);
}
"""

    /** By file name (« toon.vert »). */
    fun source(name: String): String = when (name) {
        "bright.frag" -> BRIGHT_FRAG
        "composite.frag" -> COMPOSITE_FRAG
        "down.frag" -> DOWN_FRAG
        "hud.frag" -> HUD_FRAG
        "hud.vert" -> HUD_VERT
        "lines.frag" -> LINES_FRAG
        "lines.vert" -> LINES_VERT
        "lines_xray.frag" -> LINES_XRAY_FRAG
        "ombre.frag" -> OMBRE_FRAG
        "particle.frag" -> PARTICLE_FRAG
        "particle.vert" -> PARTICLE_VERT
        "post.vert" -> POST_VERT
        "shadow.frag" -> SHADOW_FRAG
        "shadow.vert" -> SHADOW_VERT
        "sky.frag" -> SKY_FRAG
        "toon.frag" -> TOON_FRAG
        "toon.vert" -> TOON_VERT
        "up.frag" -> UP_FRAG
        "water.frag" -> WATER_FRAG
        else -> throw IllegalArgumentException("No shader named " + name)
    }
}
