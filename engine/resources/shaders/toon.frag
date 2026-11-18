#version 450
// Sillage — rendu « toon » adouci : bandes de lumière aux transitions douces, ombres teintées de
// bleu, lumière du ciel et du sol, liseré lumineux, brume vers la couleur de l'horizon.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(push_constant) uniform Push { mat4 viewProj; vec4 eye; } pc;
layout(location=0) out vec4 outColor;

// The scene's light and haze (Lighting): specialisation constants, set when the pipeline is made.
layout(constant_id = 0) const float SUN_X = -0.4533;  layout(constant_id = 1) const float SUN_Y = 0.8059;  layout(constant_id = 2) const float SUN_Z = 0.4030;
layout(constant_id = 3) const float SUN_R = 1.00;     layout(constant_id = 4) const float SUN_G = 0.95;    layout(constant_id = 5) const float SUN_B = 0.84;
layout(constant_id = 6) const float SHADE_R = 0.50;   layout(constant_id = 7) const float SHADE_G = 0.56;  layout(constant_id = 8) const float SHADE_B = 0.80;
layout(constant_id = 9) const float SKY_R = 0.42;     layout(constant_id = 10) const float SKY_G = 0.56;   layout(constant_id = 11) const float SKY_B = 0.72;
layout(constant_id = 12) const float GROUND_R = 0.42; layout(constant_id = 13) const float GROUND_G = 0.36; layout(constant_id = 14) const float GROUND_B = 0.26;
layout(constant_id = 15) const float HORIZON_R = 0.60; layout(constant_id = 16) const float HORIZON_G = 0.80; layout(constant_id = 17) const float HORIZON_B = 0.86;
layout(constant_id = 18) const float FOG_START = 45.0; layout(constant_id = 19) const float FOG_END = 150.0;

const vec3 SUN_DIR    = vec3(SUN_X, SUN_Y, SUN_Z);
const vec3 SUN        = vec3(SUN_R, SUN_G, SUN_B);          // warm light
const vec3 SHADE      = vec3(SHADE_R, SHADE_G, SHADE_B);    // shaded side: soft blue-violet
const vec3 SKY_AMB    = vec3(SKY_R, SKY_G, SKY_B);          // sky, from above
const vec3 GROUND_AMB = vec3(GROUND_R, GROUND_G, GROUND_B); // warm bounce from the ground, from below
const vec3 HORIZON    = vec3(HORIZON_R, HORIZON_G, HORIZON_B); // the sky's horizon colour

vec3 toSrgb(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)); }

void main() {
    // Sans normale : ciel et interface, couleurs telles quelles.
    if (dot(normal, normal) < 1e-4) { outColor = vec4(toSrgb(color), 1.0); return; }
    vec3 n = normalize(normal);
    vec3 v = normalize(pc.eye.xyz - world);
    float ndl = dot(n, SUN_DIR);
    // Deux transitions adoucies : le terminateur, puis une bande plus claire face au soleil.
    float lit = smoothstep(-0.06, 0.12, ndl);
    float high = smoothstep(0.62, 0.78, ndl);
    vec3 light = mix(SHADE * 0.72, SUN, lit) + SUN * high * 0.10;
    // Lumière d'ambiance : le ciel au-dessus, le sol en dessous.
    vec3 ambient = mix(GROUND_AMB, SKY_AMB, n.y * 0.5 + 0.5) * 0.22;
    vec3 c = color * (light * 0.86 + ambient);
    // Liseré : le bord des formes s'éclaire un peu, surtout côté soleil.
    // Réservé aux silhouettes : un sol vu de biais (normale vers le ciel) ne doit pas blanchir.
    float rim = pow(1.0 - max(dot(n, v), 0.0), 4.0) * (1.0 - smoothstep(0.35, 0.8, n.y));
    c += rim * (0.03 + 0.09 * lit) * vec3(1.0, 0.96, 0.88);
    // Brume : les lointains rejoignent l'horizon.
    float d = length(world - pc.eye.xyz);
    float fog = smoothstep(FOG_START, FOG_END, d);
    c = mix(c, HORIZON, fog);
    outColor = vec4(toSrgb(c), 1.0);
}
