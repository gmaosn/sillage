#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — particles: a square facing the camera per particle (six vertices, no mesh).
layout(location=0) in vec4 inPlace;   // centre (xyz), size (w; negative: it glows, added to the light behind)
layout(location=1) in vec4 inColor;   // colour, opacity
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec2 corner;
layout(location=1) out vec4 tint;
layout(location=2) out vec3 world;
layout(location=3) out float glows;
#include "frame.glsl"
const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));
void main() {
    corner = CORNERS[gl_VertexIndex];
    world = inPlace.xyz + (fr.camRight.xyz * corner.x + fr.camUp.xyz * corner.y) * abs(inPlace.w);
    gl_Position = pc.viewProj * vec4(world, 1.0);
    tint = inColor;
    glows = inPlace.w < 0.0 ? 1.0 : 0.0;
}
