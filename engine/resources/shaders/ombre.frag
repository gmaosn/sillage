#version 450
// Sillage — a soft round shadow: a dark disc whose opacity comes from the vertices' red (1 at the centre,
// 0 at the rim), blended over the ground (the distance in alpha stays as it was).
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=0) out vec4 outColor;
void main() {
    float a = color.r;
    outColor = vec4(0.10, 0.12, 0.22, 0.38 * a * a);
}
