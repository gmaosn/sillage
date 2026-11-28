#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
for f in toon.vert toon.frag toon_grid.frag sky.frag ombre.frag shadow.vert shadow.frag lamp_shadow.vert water.frag water_grid.frag hud.vert hud.frag post.vert bright.frag down.frag up.frag composite.frag particle.vert particle.frag particle_grid.frag lines.vert lines.frag lines_xray.frag; do
    glslc --target-env=vulkan1.0 "engine/resources/shaders/$f" -o "engine/resources/shaders/$f.spv"
done
