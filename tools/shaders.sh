#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
for f in toon.vert toon.frag ombre.frag; do
    glslc --target-env=vulkan1.0 "engine/resources/shaders/$f" -o "engine/resources/shaders/$f.spv"
done
