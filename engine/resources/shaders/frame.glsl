// Sillage — what every scene shader knows of the frame (VulkanRenderer.writeFrame): the light of the hour,
// the sky, the haze, the wind and the point lights near the camera. Set 0, binding 1.
#define MAX_LIGHTS 16
layout(set = 0, binding = 1) uniform Frame {
    vec4 sunDir;      // towards the main light (the sun by day, the moon by night); w: night, 0 by day … 1 at night
    vec4 sunColor;    // its colour; w: where the haze starts (m)
    vec4 shade;       // colour of the sides turned away from it; w: where the haze hides everything (m)
    vec4 skyAmb;      // ambient light from the sky; w: number of point lights
    vec4 groundAmb;   // ambient light from the ground; w: side of the shadow map in texels
    vec4 horizon;     // the sky at the horizon, and the haze
    vec4 middle;      // the sky a quarter of the way up
    vec4 zenith;      // the sky straight up
    vec4 sun;         // the sun itself: direction (xyz), how much of it shows (w)
    vec4 moon;        // the moon: direction, how much of it shows
    vec4 wind;        // xy: the wind on the ground plane (x, z), its strength as length
    vec4 camRight;    // the camera's right and up, for things that face it
    vec4 camUp;
    vec4 glow;        // colour of the glow around a low sun
    vec4 lightPos[MAX_LIGHTS];    // xyz, w: radius (m)
    vec4 lightColor[MAX_LIGHTS];  // colour × intensity
} fr;

// The sky's colour in direction d (normalised): a gradient from the horizon to the zenith and the glow
// around the sun, wide and warm when the sun is low.
vec3 skyAt(vec3 d) {
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = h < 0.25 ? mix(fr.horizon.rgb, fr.middle.rgb, smoothstep(0.0, 0.25, h))
                      : mix(fr.middle.rgb, fr.zenith.rgb, smoothstep(0.25, 1.0, h));
    float s = max(dot(d, fr.sun.xyz), 0.0);
    float low = 1.0 - smoothstep(0.0, 0.45, fr.sun.y);
    float wide = pow(s, 5.0) * (0.06 + 0.5 * low) * (1.0 - 0.7 * h);
    c += fr.glow.rgb * (wide + pow(s, 32.0) * 0.4) * fr.sun.w;
    float m = max(dot(d, fr.moon.xyz), 0.0);
    c += vec3(0.30, 0.36, 0.52) * pow(m, 24.0) * 0.25 * fr.moon.w;
    return c;
}

// A swaying point (grass, flowers, leaves) pushed by the wind: [sway] is how far its tip may bend (m).
vec3 swayed(vec3 world, float sway, float t) {
    if (sway <= 0.0) return world;
    float phase = dot(world.xz, vec2(0.23, 0.17));
    float gust = 0.6 + 0.4 * sin(t * 0.8 + phase * 0.5);
    float flutter = 0.35 * sin(t * 2.7 + phase * 3.1) + 0.15 * sin(t * 5.9 + phase * 6.3);
    vec2 push = fr.wind.xy * sway * (gust + flutter);
    return world + vec3(push.x, -0.35 * dot(push, push) / max(sway, 1e-3), push.y);
}
