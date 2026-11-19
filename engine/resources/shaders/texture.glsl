// Sillage — textures (Texture). Detail textures: a pattern that shades a surface's colour, mid-grey leaving
// it as it is, laid on the object's own space either from the three axes, blended by the normal (no seams
// on round shapes), or along each flat face, rows level and climbing the slope (roof tiles, planks).
// Images painted on a model: read at its texture coordinates, sRGB turned into linear light, with alpha.
layout(set = 0, binding = 2) uniform sampler2DArray detailMaps;

vec3 linearFromSrgb(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }

// rgb: what the surface's colour is multiplied by; a: its alpha (1 for detail textures).
vec4 detailShade(vec3 p, vec3 n, vec4 d, vec2 uv) {
    if (d.y <= 0.0) return vec4(1.0);
    if (d.z > 1.5) { vec4 t = texture(detailMaps, vec3(uv, d.x)); return vec4(linearFromSrgb(t.rgb), t.a); }
    vec3 t;
    if (d.z > 0.5) {
        // Along the face: its true normal from the screen derivatives, then level and up-slope axes.
        vec3 f = cross(dFdx(p), dFdy(p));
        f = dot(f, f) > 1e-20 ? normalize(f) : vec3(0.0, 1.0, 0.0);
        vec2 uv;
        if (abs(f.y) > 0.97) uv = p.xz;
        else {
            vec3 u = normalize(cross(vec3(0.0, 1.0, 0.0), f));
            vec3 v = cross(f, u);
            uv = vec2(dot(p, u), dot(p, v));
        }
        t = texture(detailMaps, vec3(uv, d.x)).rgb;
    } else {
        // Derivatives taken before the branches below, which differ from pixel to pixel.
        vec3 dx = dFdx(p), dy = dFdy(p);
        vec3 w = pow(abs(n), vec3(4.0));
        w /= max(w.x + w.y + w.z, 1e-5);
        t = vec3(0.0);
        if (w.x > 0.02) t += textureGrad(detailMaps, vec3(p.zy, d.x), dx.zy, dy.zy).rgb * w.x;
        if (w.y > 0.02) t += textureGrad(detailMaps, vec3(p.xz, d.x), dx.xz, dy.xz).rgb * w.y;
        if (w.z > 0.02) t += textureGrad(detailMaps, vec3(p.xy, d.x), dx.xy, dy.xy).rgb * w.z;
        t /= max((w.x > 0.02 ? w.x : 0.0) + (w.y > 0.02 ? w.y : 0.0) + (w.z > 0.02 ? w.z : 0.0), 1e-5);
    }
    return vec4(mix(vec3(1.0), t * 2.0, d.y), 1.0);
}
