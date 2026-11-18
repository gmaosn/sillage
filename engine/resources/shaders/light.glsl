// Sillage — where a point stands in the main light's view, for the shadow map (included by the vertex
// shaders, after frame.glsl). The view is an orthographic box: the map's square, push constant `shadow`
// (centre xyz, half side w), and SHADOW_DEPTH metres towards and away from the light.
const float SHADOW_DEPTH = 80.0;

// x, y: the point on the map (−1..1), z: its depth (0 nearest the light, 1 farthest).
vec3 sunView(vec3 world, vec4 shadow) {
    vec3 l = fr.sunDir.xyz;
    vec3 ref = abs(l.y) > 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(ref, l));
    vec3 up = cross(l, right);
    vec3 d = world - shadow.xyz;
    return vec3(dot(d, right) / shadow.w, dot(d, up) / shadow.w, 0.5 - dot(d, l) / (2.0 * SHADOW_DEPTH));
}
