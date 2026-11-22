# A Raspberry Pi OS-like userland (Debian, ARM 64 bits) with Mesa's software Vulkan: where the ARM build of a
# game is tried on an x86 computer (QEMU emulates the processor). No GPU: lavapipe draws.
FROM debian:trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends libvulkan1 mesa-vulkan-drivers libxcb1 libxkbcommon0 libxkbcommon-x11-0 \
    libwayland-client0 libasound2 xvfb xauth libegl1 libgles2 file ca-certificates && rm -rf /var/lib/apt/lists/*
