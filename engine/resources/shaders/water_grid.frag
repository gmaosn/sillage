#version 450
#extension GL_GOOGLE_include_directive : require
// Sillage — water (water.glsl) on a frame with more than 16 lamps: each point reads the light grid.
#define LIGHT_GRID
#include "water.glsl"
