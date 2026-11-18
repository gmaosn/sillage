// Sillage — the scene's light, shared by the fragment shaders (toon.frag, water.frag). Before including
// it, a shader declares its push constants `pc` (with `shadow` and `eye`), its input `sun` (place in the
// shadow map) and the shadow map at set 0, binding 0.

// The scene's light and haze (Lighting): specialisation constants, set when the pipeline is made.
layout(constant_id = 0) const float SUN_X = -0.4533;  layout(constant_id = 1) const float SUN_Y = 0.8059;  layout(constant_id = 2) const float SUN_Z = 0.4030;
layout(constant_id = 3) const float SUN_R = 1.00;     layout(constant_id = 4) const float SUN_G = 0.95;    layout(constant_id = 5) const float SUN_B = 0.84;
layout(constant_id = 6) const float SHADE_R = 0.50;   layout(constant_id = 7) const float SHADE_G = 0.56;  layout(constant_id = 8) const float SHADE_B = 0.80;
layout(constant_id = 9) const float SKY_R = 0.42;     layout(constant_id = 10) const float SKY_G = 0.56;   layout(constant_id = 11) const float SKY_B = 0.72;
layout(constant_id = 12) const float GROUND_R = 0.42; layout(constant_id = 13) const float GROUND_G = 0.36; layout(constant_id = 14) const float GROUND_B = 0.26;
layout(constant_id = 15) const float HORIZON_R = 0.60; layout(constant_id = 16) const float HORIZON_G = 0.80; layout(constant_id = 17) const float HORIZON_B = 0.86;
layout(constant_id = 18) const float FOG_START = 45.0; layout(constant_id = 19) const float FOG_END = 150.0;
layout(constant_id = 20) const float SHADOW_TEXELS = 2048.0;

const vec3 SUN_DIR    = vec3(SUN_X, SUN_Y, SUN_Z);
const vec3 SUN        = vec3(SUN_R, SUN_G, SUN_B);          // warm light
const vec3 SHADE      = vec3(SHADE_R, SHADE_G, SHADE_B);    // shaded side: soft blue-violet
const vec3 SKY_AMB    = vec3(SKY_R, SKY_G, SKY_B);          // sky, from above
const vec3 GROUND_AMB = vec3(GROUND_R, GROUND_G, GROUND_B); // warm bounce from the ground, from below
const vec3 HORIZON    = vec3(HORIZON_R, HORIZON_G, HORIZON_B); // the sky's horizon colour

vec3 toSrgb(vec3 c) { return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)); }

// How much of the sun reaches this point: 0 in another thing's shadow, 1 in full light. Nine filtered
// lookups soften the edge; the shadows fade out towards the border of the map.
float sunlight() {
    if (pc.shadow.w <= 0.0) return 1.0;
    float edge = max(abs(sun.x), abs(sun.y));
    if (edge >= 1.0) return 1.0;
    vec2 uv = sun.xy * 0.5 + 0.5;
    float texel = 1.25 / SHADOW_TEXELS;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec3(uv + vec2(i, j) * texel, sun.z));
    return mix(sum / 9.0, 1.0, smoothstep(0.8, 1.0, edge));
}

// The haze: far things join the horizon's colour.
vec3 hazed(vec3 c, vec3 world) {
    return mix(c, HORIZON, smoothstep(FOG_START, FOG_END, length(world - pc.eye.xyz)));
}
