#version 450
// Sillage — debug lines where they are seen. The alpha of the scene image (the distance to the eye, for the
// ink lines) is left as it was: the pipeline writes red, green and blue only.
layout(location=0) in vec4 tint;
layout(location=0) out vec4 outColor;
void main() { outColor = tint; }
