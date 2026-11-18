#version 450
// Sillage — vertex shader : position projetée, normale et position dans le monde.
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
// Per instance: the model matrix as its three affine rows (one draw call for every copy of a mesh).
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
// 80 bytes: view × projection, eye (xyz) and time (w).
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; } pc;
layout(location=0) out vec3 normal;
layout(location=1) out vec3 color;
layout(location=2) out vec3 world;
void main() {
    vec4 p = vec4(inPosition, 1.0);
    world = vec3(dot(row0, p), dot(row1, p), dot(row2, p));
    gl_Position = pc.viewProj * vec4(world, 1.0);
    // Échelles uniformes seulement : la partie 3×3 tourne la normale sans la déformer.
    normal = vec3(dot(row0.xyz, inNormal), dot(row1.xyz, inNormal), dot(row2.xyz, inNormal));
    color = inColor;
}
