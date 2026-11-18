#version 450
// Sillage — ombre portée douce : un disque sombre dont l'opacité vient du rouge des sommets
// (1 au centre, 0 au bord), mélangé au sol par transparence.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=0) out vec4 outColor;
void main() {
    float a = color.r;
    outColor = vec4(0.10, 0.12, 0.22, 0.38 * a * a);
}
