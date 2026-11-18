#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
for stage in vert frag; do
    glslc --target-env=vulkan1.0 "engine/resources/shaders/toon.$stage" -o "engine/resources/shaders/toon.$stage.spv"
done
