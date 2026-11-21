#version 450
// Sillage — the interface over the finished picture, blended by opacity. Kinds of shape:
// 0 plain (the pixel font, square panels); 1 a glyph read from the atlas (shape.y > 0: its outline, that many
// pixels wide); 2 a rounded box and 3 a ring, from their distance to the edge (corner radius shape.y, half size
// shape.zw in pixels, edge softness extra.x, ring width extra.y).
layout(location=0) in vec3 color;
layout(location=1) in float opacity;
layout(location=2) in vec2 uv;
layout(location=3) flat in vec4 shape;
layout(location=4) flat in vec4 extra;
layout(set=0, binding=0) uniform sampler2D atlas;
layout(push_constant) uniform Push { vec4 size; } pc;     // the atlas's width and height in texels
layout(location=0) out vec4 outColor;

float glyph(vec2 t) { return texture(atlas, t * pc.size.zw).r; }

void main() {
    float a = opacity;
    int kind = int(shape.x + 0.5);
    if (kind == 1) {
        float c = glyph(uv);
        float r = min(shape.y, 4.0);
        if (r > 0.0) {
            // The outline: the most ink within r pixels, on two rings of taps.
            for (int i = 0; i < 16; i++) {
                float t = float(i) * 0.39269908;
                vec2 d = vec2(cos(t), sin(t));
                c = max(c, glyph(uv + d * r));
                c = max(c, glyph(uv + d * r * 0.5));
            }
        }
        a *= c;
    } else if (kind >= 2) {
        vec2 q = abs(uv) - shape.zw + vec2(shape.y);
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - shape.y;
        float soft = max(extra.x, 0.5);
        float inside = 1.0 - smoothstep(-0.5 * soft, 0.5 * soft, d);
        if (kind == 3) inside *= smoothstep(-0.5 * soft, 0.5 * soft, d + extra.y);
        a *= inside;
    }
    outColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), a);
}
