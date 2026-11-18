// Sillage — where a point stands in the sun's view, for the shadow map (included by the vertex shaders).
// The sun's view is an orthographic box: the map's square, push constant `shadow` (centre xyz, half side w),
// and SHADOW_DEPTH metres towards and away from the sun. Specialisation constants 0–2: the sun's direction.
layout(constant_id = 0) const float SUN_X = -0.4533;
layout(constant_id = 1) const float SUN_Y = 0.8059;
layout(constant_id = 2) const float SUN_Z = 0.4030;
const float SHADOW_DEPTH = 80.0;

// x, y: the point on the map (−1..1), z: its depth (0 nearest the sun, 1 farthest).
vec3 sunView(vec3 world, vec4 shadow) {
    vec3 l = normalize(vec3(SUN_X, SUN_Y, SUN_Z));
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = world - shadow.xyz;
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * SHADOW_DEPTH));
}
