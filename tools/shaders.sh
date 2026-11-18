#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
for f in toon.vert toon.frag sky.frag ombre.frag shadow.vert water.frag hud.vert hud.frag post.vert bright.frag down.frag up.frag composite.frag particle.vert particle.frag; do
    glslc --target-env=vulkan1.0 "engine/resources/shaders/$f" -o "engine/resources/shaders/$f.spv"
done
