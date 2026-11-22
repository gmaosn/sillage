#!/bin/sh
# A small Linux userland as a tarball (Debian with Mesa's software Vulkan and the libraries Sillage calls),
# for a system that runs Linux programs through an emulation layer: NetBSD's compat_linux (docs/PLATFORMS.md).
#   tools/linux-rootfs.sh [amd64|arm64] > linux-rootfs.tar.gz
set -eu
arch=${1:-amd64}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
docker build -q --platform "linux/$arch" -t "sillage-rootfs-$arch" "$root/tools/arm64" >&2
c=$(docker create --platform "linux/$arch" "sillage-rootfs-$arch")
trap 'docker rm "$c" >/dev/null' EXIT
docker export "$c" | gzip -1
