#version 450
// Sillage — the glow, going up: the smaller level spread with a tent of eight taps, plus this level.
layout(location=0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D smaller;
layout(set = 0, binding = 1) uniform sampler2D level;
layout(push_constant) uniform Post { vec4 texel; vec4 params; } pc;   // texel: 1 / the smaller level's size
layout(location=0) out vec4 outColor;
void main() {
    vec2 o = pc.texel.xy;
    vec3 c = texture(smaller, uv + vec2(-o.x, 0.0)).rgb + texture(smaller, uv + vec2(o.x, 0.0)).rgb
           + texture(smaller, uv + vec2(0.0, -o.y)).rgb + texture(smaller, uv + vec2(0.0, o.y)).rgb
           + (texture(smaller, uv + vec2(-o.x, -o.y) * 0.5).rgb + texture(smaller, uv + vec2(o.x, -o.y) * 0.5).rgb
            + texture(smaller, uv + vec2(-o.x, o.y) * 0.5).rgb + texture(smaller, uv + vec2(o.x, o.y) * 0.5).rgb) * 2.0;
    outColor = vec4(c / 12.0 + texture(level, uv).rgb, 1.0);
}
