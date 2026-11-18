#version 450
// Sillage — the interface: positions already on screen, plain colours, the opacity in the normal's x.
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec3 color;
layout(location=1) out float opacity;
void main() {
    gl_Position = pc.viewProj * vec4(inPosition, 1.0);
    color = inColor;
    opacity = 1.0 - inNormal.x;
}
