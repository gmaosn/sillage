#version 450
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(location=0) out vec4 outColor;
void main() {
    if (dot(normal,normal) < 0.01) { outColor=vec4(color,1); return; }
    float light = dot(normalize(normal),normalize(vec3(-0.45,0.85,0.35)));
    float band = light > 0.55 ? 1.0 : (light > 0.05 ? 0.76 : 0.48);
    vec3 shaded = color * band + vec3(0.02,0.035,0.035);
    float fog = smoothstep(40.0,130.0,length(world.xz));
    shaded = mix(shaded,vec3(0.31,0.68,0.77),fog);
    outColor = vec4(pow(clamp(shaded,0.0,1.0),vec3(1.0/2.2)),1.0);
}
