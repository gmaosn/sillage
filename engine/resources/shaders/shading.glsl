// Sillage — the scene's light, shared by the fragment shaders (toon.frag, water.frag, particle.frag). Before
// including it, a shader declares its push constants `pc` (with `shadow` and `eye`; shadow.w > 0: cast shadows
// on), the shadow maps at set 0, binding 0 (one layer per cascade), and includes frame.glsl and light.glsl.

// The lamps' shadows: six layers per lamp that has one, a face of a cube around it each (RenderBatches.lampFace).
layout(set = 0, binding = 3) uniform sampler2DArrayShadow lampShadows;
#ifdef LIGHT_GRID
// The light grid: per cluster four texels, the first's red its number of lamps, then their places in the frame's list.
// Only the *_grid.frag variants read it, drawn on frames with more than 16 lamps: the others keep their registers.
layout(set = 0, binding = 4) uniform sampler2D lightGrid;
#endif

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

// How much of a lamp's light reaches the point [l] away from it (1: all of it) on a surface of normal n: its
// shadow cube [slot], the face the point is seen through, nine filtered lookups. The faces see a little more than
// a right angle (scale s), so that the lookups near a face's edge stay on it. [r]: the lamp's radius, the far end;
// what stands nearer than a tenth of it (the lamp's own fixture: a fire's logs, a brazier's bowl) casts nothing.
float lampLight(int slot, vec3 l, float r, vec3 n) {
    float size = fr.lightSlices.w;
    l += n * (length(l) * 3.0 / size);                  // a little off the surface: about a texel at that distance
    vec3 a = abs(l);
    float face; vec3 q;                                  // q: across and up the face, then along its axis
    if (a.x >= a.y && a.x >= a.z) { face = l.x > 0.0 ? 0.0 : 1.0; q = vec3(l.x > 0.0 ? -l.z : l.z, l.y, a.x); }
    else if (a.y >= a.z) { face = l.y > 0.0 ? 2.0 : 3.0; q = vec3(l.x, l.y > 0.0 ? -l.z : l.z, a.y); }
    else { face = l.z > 0.0 ? 4.0 : 5.0; q = vec3(l.z > 0.0 ? l.x : -l.x, l.y, a.z); }
    float s = 1.0 - 6.0 / size;
    float near = clamp(r * 0.1, 0.15, 0.8);
    vec2 uv = q.xy / q.z * (0.5 * s) + 0.5;
    float depth = r / (r - near) * (1.0 - near / q.z);
    float texel = 1.6 / size;
    float layer = float(slot * 6) + face;
    return 0.25 * (texture(lampShadows, vec4(uv + vec2(-texel, -texel), layer, depth)) + texture(lampShadows, vec4(uv + vec2(texel, -texel), layer, depth))
                 + texture(lampShadows, vec4(uv + vec2(-texel, texel), layer, depth)) + texture(lampShadows, vec4(uv + vec2(texel, texel), layer, depth)));
}

// Lamp [i]'s light on a surface of normal n at p (see pointLights).
vec3 lampAt(int i, vec3 n, vec3 p, bool shadows) {
    vec3 l = fr.lightPos[i].xyz - p;
    float r = fr.lightPos[i].w;
    float d2 = dot(l, l);
    if (d2 >= r * r) return vec3(0.0);
    float d = sqrt(d2);
    float x2 = d2 / (r * r);
    float fall = (1.0 - x2) * (1.0 - x2) / (1.0 + 12.0 * x2);
    float ndl = n == vec3(0.0) ? 1.0 : dot(n, l / max(d, 1e-4));
    // In a lamp's shadow half its light stays (a flame is wide, the air around it glows): soft shadows, never holes.
    if (shadows && fr.lightColor[i].w > 0.5) fall *= 0.5 + 0.5 * lampLight(int(fr.lightColor[i].w) - 1, -l, r, n);
    return fr.lightColor[i].rgb * fall * (0.25 + 0.75 * smoothstep(-0.1, 0.3, ndl));
}

// The point lights' light on a surface of normal n at p: soft toon steps, bright near the lamp and
// falling off like the square of the distance, smoothly to nothing at each light's radius; [shadows]: the
// lamps that have a shadow cube cast their shadows. Up to 16 lamps a point looks at every one; with more
// (the LIGHT_GRID variants), only at those of its cluster: its tile of the picture, its slice of distance.
vec3 pointLights(vec3 n, vec3 p, bool shadows) {
    vec3 sum = vec3(0.0);
    int count = int(fr.skyAmb.w);
#ifndef LIGHT_GRID
    for (int i = 0; i < count; i++) sum += lampAt(i, n, p, shadows);
#else
    ivec2 tile = min(ivec2(gl_FragCoord.xy * fr.lightGrid.zw), ivec2(fr.lightGrid.xy) - 1);
    float dist = length(p - pc.eye.xyz);
    int slice = dist < fr.lightSlices.x ? 0 : min(int(fr.lightSlices.z) - 1, 1 + int(log2(dist / fr.lightSlices.x) * fr.lightSlices.y));
    ivec2 cell = ivec2(4 * (tile.y * int(fr.lightGrid.x) + tile.x), slice);
    count = int(texelFetch(lightGrid, cell, 0).r * 255.0 + 0.5);
    for (int k = 1; k <= count; k++) sum += lampAt(int(texelFetch(lightGrid, cell + ivec2(k / 4, 0), 0)[k % 4] * 255.0 + 0.5), n, p, shadows);
#endif
    return sum;
}

// The haze: far things join the colour of the sky behind them.
vec3 hazed(vec3 c, vec3 world) {
    vec3 d = world - pc.eye.xyz;
    float far = length(d);
    vec3 dir = d / max(far, 1e-4);
    return mix(c, skyAt(vec3(dir.x, max(dir.y, 0.0) * 0.3, dir.z)), smoothstep(fr.sunColor.w, fr.shade.w, far));
}
