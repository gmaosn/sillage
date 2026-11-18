#version 450
// Sillage — rendu « toon » adouci : bandes de lumière aux transitions douces, ombres teintées de
// bleu, lumière du ciel et du sol, liseré lumineux, brume vers la couleur de l'horizon.
layout(location=0) in vec3 normal;
layout(location=1) in vec3 color;
layout(location=2) in vec3 world;
layout(push_constant) uniform Push { mat4 mvp; vec4 row0; vec4 row1; vec4 row2; vec4 eye; } pc;
layout(location=0) out vec4 outColor;

const vec3 SUN_DIR    = normalize(vec3(-0.45, 0.80, 0.40));
const vec3 SUN        = vec3(1.00, 0.95, 0.84);   // lumière chaude
const vec3 SHADE      = vec3(0.50, 0.56, 0.80);   // côté ombre : bleu-violet doux
const vec3 SKY_AMB    = vec3(0.42, 0.56, 0.72);   // ciel, par-dessus
const vec3 GROUND_AMB = vec3(0.42, 0.36, 0.26);   // rebond chaud du sol, par-dessous
const vec3 HORIZON    = vec3(0.60, 0.80, 0.86);   // doit égaler l'horizon du ciel

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
    float fog = smoothstep(45.0, 150.0, d);
    c = mix(c, HORIZON, fog);
    outColor = vec4(toSrgb(c), 1.0);
}
