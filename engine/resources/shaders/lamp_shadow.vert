#version 450
// Sillage — a lamp's shadow: the depth of what stands around a lamp, seen from it through one face of the cube
// around it (RenderBatches.lampFace: view × projection in the push constants). Not swayed by the wind: a lamp's
// shadow is drawn again only when something within its reach moves.
layout(location=0) in vec3 inPosition;
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
layout(location=7) in vec4 inTexture;
layout(location=8) in vec4 inImage;
layout(location=0) out vec2 uv;
layout(location=1) flat out vec2 cut;              // an image's layer and the alpha below which it is cut out (0: opaque)
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
void main() {
    vec4 p = vec4(inPosition, 1.0);
    gl_Position = pc.viewProj * vec4(dot(row0, p), dot(row1, p), dot(row2, p), 1.0);
    // Only images painted on a model are cut out (mapping 2, see toon.vert and texture.glsl).
    uv = inImage.xy;
    cut = vec2(inTexture.x - 1.0, inTexture.x > 0.5 && inTexture.w > 1.5 ? inImage.z : 0.0);
}
