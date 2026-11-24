#!/usr/bin/env bash
# Builds the toolchains image, pushes it under every given tag, and prints the digest of the push.
# Usage: ci/toolchains/build-image.sh [--fresh] <tag>...
#   --fresh  pulls the base images and uses no layer cache (the weekly rebuild).
#   Otherwise the build reuses the JDK and tool layers of TOOLCHAINS_IMAGE (image.env).
# The caller sources ci/configure-docker.sh first: it logs in and sets the mirror of the FROM lines.
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env

cache_args=(--cache-from "$TOOLCHAINS_IMAGE")
if [ "${1:-}" = "--fresh" ]; then cache_args=(--pull --no-cache); shift; fi
[ "$#" -ge 1 ] || { echo "Usage: $0 [--fresh] <tag>..." >&2; exit 1; }

tag_args=()
for t in "$@"; do tag_args+=(-t "$t"); done
# BUILDKIT_INLINE_CACHE puts the cache metadata in the image, so the next build can reuse its layers.
docker build "${cache_args[@]}" --build-arg BUILDKIT_INLINE_CACHE=1 \
  --build-arg MIRROR="${ROR_DOCKER_HUB_MIRROR_PREFIX:-}" \
  -f ci/toolchains/JdkToolchains.Dockerfile "${tag_args[@]}" . >&2

digest=""
for t in "$@"; do
  log=$(docker push "$t" 2>&1 | tee /dev/stderr)
  digest=$(sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' <<<"$log" | head -1)
  [ -n "$digest" ] || { echo "The push of $t printed no digest." >&2; exit 1; }
done
echo "$digest"
