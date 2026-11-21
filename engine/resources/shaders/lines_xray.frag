#version 450
// Sillage — debug lines behind the scenery: drawn first without the depth test, faint, so that what is
// hidden still shows (a body inside a wall, a zone under the ground).
layout(location=0) in vec4 tint;
layout(location=0) out vec4 outColor;
void main() { outColor = vec4(tint.rgb, tint.a * 0.28); }
