#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — vertex shader: projected position, normal, world position, place in the shadow map and the
// vertex's own light.
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;
// Per instance: the model matrix as its three affine rows (one draw call for every copy of a mesh).
layout(location=3) in vec4 row0;
layout(location=4) in vec4 row1;
layout(location=5) in vec4 row2;
// Per vertex: light of its own (rgb, negative: only at night) and how far it sways in the wind (w, m);
// its detail texture (x: its number, 0 for none), the texture's tile (y, m), strength (z) and mapping (w: 0 sides, 1 faces).
layout(location=6) in vec4 inEffect;
layout(location=7) in vec4 inTexture;
// Texture coordinates (xy, for an image painted on the model) and the alpha below which it is cut out (z, 0: opaque).
layout(location=8) in vec4 inImage;
// A model's material maps (Mesh.surface): its tangent (xyz; w: the handedness of the basis); its normal map (x: its
// number, 0 for none) and how strongly it bends the normal (y), its emission map (z), its opacity when blended (w, 0: opaque).
layout(location=9) in vec4 inTangent;
layout(location=10) in vec4 inMaps;
// 128 bytes: view × projection, eye (xyz) and time (w), the shadow map's centre (xyz) and half side (w, 0: none), the water's colours.
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; vec4 shadow; vec4 water; vec4 shallows; } pc;
layout(location=0) out vec3 normal;
layout(location=1) out vec3 color;
layout(location=2) out vec3 world;
layout(location=4) out vec3 glow;
layout(location=5) out vec3 detailAt;              // the point in the object's space, in tiles of its texture
layout(location=6) out vec3 detailNormal;          // the normal in the object's space
layout(location=7) flat out vec4 detail;           // texture layer, strength, mapping, alpha cut-out
layout(location=8) out vec2 uv;
layout(location=9) out vec4 tangent;               // in the world; w: the handedness
layout(location=10) flat out vec4 maps;
#include "frame.glsl"
#include "light.glsl"
void main() {
    vec4 p = vec4(inPosition, 1.0);
    world = swayed(vec3(dot(row0, p), dot(row1, p), dot(row2, p)), inEffect.w, pc.eye.w);
    gl_Position = pc.viewProj * vec4(world, 1.0);
    // Uniform scales only: the 3×3 part turns the normal without distorting it.
    normal = vec3(dot(row0.xyz, inNormal), dot(row1.xyz, inNormal), dot(row2.xyz, inNormal));
    color = inColor;
    // The texture lies on the object: its own space, in metres (the rows' length is the node's scale).
    float scale = length(row0.xyz);
    detailAt = inPosition * scale / max(inTexture.y, 1e-3);
    detailNormal = inNormal;
    detail = vec4(inTexture.x - 1.0, inTexture.x > 0.5 ? inTexture.z : 0.0, inTexture.w, inImage.z);
    uv = inImage.xy;
    tangent = vec4(dot(row0.xyz, inTangent.xyz), dot(row1.xyz, inTangent.xyz), dot(row2.xyz, inTangent.xyz), inTangent.w);
    maps = inMaps;
    glow =any(lessThan(inEffect.rgb, vec3(0.0))) ? -inEffect.rgb * fr.sunDir.w : inEffect.rgb;
}
