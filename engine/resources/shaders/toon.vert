#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — vertex shader: projected position, normal, world position and place in the shadow map.
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
// Per instance: the model matrix as its three affine rows (one draw call for every copy of a mesh).
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
// 128 bytes: view × projection, eye (xyz) and time (w), the shadow map's centre (xyz) and half side (w, 0: none), the water's colours.
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec3 normal;
layout(location=1) out vec3 color;
layout(location=2) out vec3 world;
layout(location=3) out vec3 sun;
#include "light.glsl"
void main() {
    vec4 p = vec4(inPosition, 1.0);
    world = vec3(dot(row0, p), dot(row1, p), dot(row2, p));
    gl_Position = pc.viewProj * vec4(world, 1.0);
    // Uniform scales only: the 3×3 part turns the normal without distorting it.
    normal = vec3(dot(row0.xyz, inNormal), dot(row1.xyz, inNormal), dot(row2.xyz, inNormal));
    color = inColor;
    // Looked up a little off the surface, along its normal: a face never shadows itself.
    vec3 n = dot(normal, normal) > 1e-8 ? normalize(normal) : vec3(0.0);
    sun = pc.shadow.w > 0.0 ? sunView(world + n * (pc.shadow.w / 600.0), pc.shadow) : vec3(0.0);
}
