#version 450
// Sillage — the glow, going down: half the size, blurred (the centre and four diagonal taps).
layout(location=0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Post { vec4 texel; vec4 params; } pc;   // texel: 1 / the source's size
layout(location=0) out vec4 outColor;
void main() {
    vec2 o = pc.texel.xy;
    vec3 c = texture(source, uv).rgb * 4.0
           + texture(source, uv + vec2(-o.x, -o.y)).rgb + texture(source, uv + vec2(o.x, -o.y)).rgb
           + texture(source, uv + vec2(-o.x, o.y)).rgb + texture(source, uv + vec2(o.x, o.y)).rgb;
    outColor = vec4(c / 8.0, 1.0);
}
