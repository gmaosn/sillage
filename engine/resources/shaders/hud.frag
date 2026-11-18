#version 450
// Sillage — the interface over the scene: plain colours, blended by their opacity (1 − the normal's x).
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=0) out vec4 outColor;
void main() {
    outColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0 - normal.x);
}
