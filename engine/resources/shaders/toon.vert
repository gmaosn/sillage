#version 450
// Sillage — vertex shader : position projetée, normale et position dans le monde.
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
// 128 octets : mvp, matrice du modèle en trois lignes (affine), œil (xyz) et temps (w).
layout(push_constant) uniform Push { mat4 mvp; vec4 row0; vec4 row1; vec4 row2; vec4 eye; } pc;
layout(location=0) out vec3 normal;
layout(location=1) out vec3 color;
layout(location=2) out vec3 world;
void main() {
    vec4 p = vec4(inPosition, 1.0);
    gl_Position = pc.mvp * p;
    world = vec3(dot(pc.row0, p), dot(pc.row1, p), dot(pc.row2, p));
    // Échelles uniformes seulement : la partie 3×3 tourne la normale sans la déformer.
    normal = vec3(dot(pc.row0.xyz, inNormal), dot(pc.row1.xyz, inNormal), dot(pc.row2.xyz, inNormal));
    color = inColor;
}
