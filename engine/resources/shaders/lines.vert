#version 450
// Sillage — debug lines: two points per segment, each with its colour (the physics shapes, contacts, zones…).
layout(location=0) in vec3 inPosition;
layout(location=1) in vec4 inColor;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec4 tint;
void main() {
    gl_Position = pc.viewProj * vec4(inPosition, 1.0);
    tint = inColor;
}
