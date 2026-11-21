// Sillage — the scene's light, shared by the fragment shaders (toon.frag, water.frag). Before including
// it, a shader declares its push constants `pc` (with `shadow` and `eye`; shadow.w > 0: cast shadows on),
// the shadow maps at set 0, binding 0 (one layer per cascade), and includes frame.glsl and light.glsl.

// The main light through cascade c at this point: nine filtered lookups soften the edge. Looked up a little
// off the surface along its normal, by a share of the texel: a face never shadows itself.
float cascadeLight(int c, vec3 world, vec3 n) {
    vec4 sq = fr.cascade[c];
    vec3 s = sunView(world + n * (sq.w / 600.0), sq);
    vec2 uv = s.xy * 0.5 + 0.5;
    float texel = 1.25 / fr.groundAmb.w;
    float sum = 0.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) sum += texture(shadowMap, vec4(uv + vec2(i, j) * texel, float(c), s.z));
    return sum / 9.0;
}

// How much of the main light reaches this point: 0 in another thing's shadow, 1 in full light. The finest
// square that holds the point answers; near its border it blends into the next, and the last one fades
// out towards its own border.
float sunlight(vec3 world, vec3 n) {
    if (pc.shadow.w <= 0.0) return 1.0;
    for (int c = 0; c < 3; c++) {
        vec4 sq = fr.cascade[c];
        if (sq.w <= 0.0) break;
        vec3 s = sunView(world, sq);
        float edge = max(abs(s.x), abs(s.y));
        if (edge >= 1.0) continue;
        float here = cascadeLight(c, world, n);
        float blend = smoothstep(0.8, 1.0, edge);
        if (blend <= 0.0) return here;
        bool last = c == 2 || fr.cascade[c + 1].w <= 0.0;
        return mix(here, last ? 1.0 : cascadeLight(c + 1, world, n), blend);
    }
    return 1.0;
}

// The point lights' light on a surface of normal n at p: soft toon steps, bright near the lamp and
// falling off like the square of the distance, smoothly to nothing at each light's radius.
vec3 pointLights(vec3 n, vec3 p) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
    for (int i = 0; i < count; i++) {
        vec3 l = fr.lightPos[i].xyz - p;
        float r = fr.lightPos[i].w;
        float d2 = dot(l, l);
        if (d2 >= r * r) continue;
        float d = sqrt(d2);
        float x2 = d2 / (r * r);
        float fall = (1.0 - x2) * (1.0 - x2) / (1.0 + 12.0 * x2);
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
