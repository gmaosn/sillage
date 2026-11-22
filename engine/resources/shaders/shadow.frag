#version 450
// Sillage — the shadow map's fragments: nothing to colour, only the holes of cut-out images (a sign with
// rounded corners, leaves, a fence) so that their shadow has the shape that is drawn.
layout(set = 0, binding = 2) uniform sampler2DArray detailMaps;
layout(location=0) in vec2 uv;
layout(location=1) flat in vec2 cut;
void main() {
    if (cut.y > 0.0 && texture(detailMaps, vec3(uv, cut.x)).a < cut.y) discard;
}
