#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — the shadow map: the depth of everything that casts a shadow, seen from the sun.
layout(location=0) in vec3 inPosition;
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
#include "light.glsl"
void main() {
    vec4 p = vec4(inPosition, 1.0);
    vec3 world = vec3(dot(row0, p), dot(row1, p), dot(row2, p));
    gl_Position = vec4(sunView(world, pc.shadow), 1.0);
}
