#version 450
// Sillage — the interface: positions already in clip space; per vertex its texture or local coordinates,
// colour and transparency (1 − opacity), the kind of shape and its parameters (see ui/Painter.kt).
layout(location=0) in vec2 inPosition;
layout(location=1) in vec2 inUv;
layout(location=2) in vec4 inColor;
layout(location=3) in vec4 inShape;
layout(location=4) in vec4 inExtra;
layout(location=0) out vec3 color;
layout(location=1) out float opacity;
layout(location=2) out vec2 uv;
layout(location=3) flat out vec4 shape;
layout(location=4) flat out vec4 extra;
void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    color = inColor.rgb;
    opacity = 1.0 - inColor.a;
    uv = inUv;
    shape = inShape;
    extra = inExtra;
}
