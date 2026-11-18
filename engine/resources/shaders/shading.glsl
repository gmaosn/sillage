// Sillage — the scene's light, shared by the fragment shaders (toon.frag, water.frag). Before including
// it, a shader declares its push constants `pc` (with `shadow` and `eye`), its input `sun` (place in the
// shadow map), the shadow map at set 0, binding 0, and includes frame.glsl.

// How much of the main light reaches this point: 0 in another thing's shadow, 1 in full light. Nine
// filtered lookups soften the edge; the shadows fade out towards the border of the map.
float sunlight() {
    if (pc.shadow.w <= 0.0) return 1.0;
    float edge = max(abs(sun.x), abs(sun.y));
    if (edge >= 1.0) return 1.0;
    vec2 uv = sun.xy * 0.5 + 0.5;
    float texel = 1.25 / fr.groundAmb.w;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec3(uv + vec2(i, j) * texel, sun.z));
    return mix(sum / 9.0, 1.0, smoothstep(0.8, 1.0, edge));
}

// The point lights' light on a surface of normal n at p: soft toon steps, fading to nothing at each
// light's radius.
vec3 pointLights(vec3 n, vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - p;
        float r = fr.lightPos[i].w;
        float d2 = dot(l, l);
        if (d2 >= r * r) continue;
        float d = sqrt(d2);
        float fall = 1.0 - d2 / (r * r);
        fall *= fall;
        float ndl = n == vec3(0.0) ? 1.0 : dot(n, l / max(d, 1e-4));
        sum += fr.lightColor[i].rgb * fall * (0.25 + 0.75 * smoothstep(-0.1, 0.3, ndl));
    }
    return sum;
}

// The haze: far things join the colour of the sky behind them.
vec3 hazed(vec3 c, vec3 world) {
    vec3 d = world - pc.eye.xyz;
    float far = length(d);
    vec3 dir = d / max(far, 1e-4);
    return mix(c, skyAt(vec3(dir.x, max(dir.y, 0.0) * 0.3, dir.z)), smoothstep(fr.sunColor.w, fr.shade.w, far));
}
