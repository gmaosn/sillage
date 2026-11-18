#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
for f in toon.vert toon.frag ombre.frag shadow.vert water.frag hud.frag; do
    glslc --target-env=vulkan1.0 "engine/resources/shaders/$f" -o "engine/resources/shaders/$f.spv"
done
