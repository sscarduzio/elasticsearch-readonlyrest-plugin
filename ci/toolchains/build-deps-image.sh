#!/usr/bin/env bash
# Builds and pushes the deps tag of this commit (ci/toolchains/deps-tag.sh) when it is missing.
# Usage: ci/toolchains/build-deps-image.sh [--fresh] [--develop]
#   --fresh    builds even when the tag exists, from fresh base images and an empty Gradle home (weekly rebuild).
#   --develop  also points <prefix>-develop at the deps tag.
# Otherwise the build reuses the layers and the Gradle home of <prefix>-develop, so it downloads only new jars.
# Needs DOCKER_REGISTRY_USER / DOCKER_REGISTRY_PASSWORD with push rights.
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env
# shellcheck source=ci/log.sh
source ci/log.sh

fresh=false develop=false
for arg in "$@"; do
  case "$arg" in
    --fresh) fresh=true ;;
    --develop) develop=true ;;
    *) echo "Usage: $0 [--fresh] [--develop]" >&2; exit 1 ;;
  esac
done
deps_tag=$(ci/toolchains/deps-tag.sh)
develop_tag="${TOOLCHAINS_TAG_PREFIX}-develop"

# shellcheck source=ci/configure-docker.sh
source ci/configure-docker.sh >&2

digest=$(ci/toolchains/registry-digest.sh "$deps_tag") || { ci_log "Docker Hub cannot say whether $deps_tag exists."; exit 1; }
if [ -z "$digest" ] || [ "$fresh" = true ]; then
  if [ "$fresh" = true ]; then
    build_args=(--pull --no-cache)
  else
    build_args=(--cache-from "$develop_tag" --build-arg GRADLE_HOME_SEED="$develop_tag")
  fi
  ci_log "Building $deps_tag."
  # BUILDKIT_INLINE_CACHE puts the cache metadata in the image, so the next build can reuse its layers.
  docker build "${build_args[@]}" --build-arg BUILDKIT_INLINE_CACHE=1 \
    --build-arg MIRROR="${ROR_DOCKER_HUB_MIRROR_PREFIX:-}" \
    -f ci/toolchains/JdkToolchains.Dockerfile -t "$deps_tag" . >&2
  log=$(docker push "$deps_tag" 2>&1 | tee /dev/stderr)
  digest=$(sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' <<<"$log" | head -1)
  [ -n "$digest" ] || { ci_log "The push of $deps_tag printed no digest."; exit 1; }
else
  ci_log "$deps_tag exists."
fi

if [ "$develop" = true ] && [ "$(ci/toolchains/registry-digest.sh "$develop_tag")" != "$digest" ]; then
  ci_log "Pointing $develop_tag at $deps_tag."
  docker buildx imagetools create -t "$develop_tag" "${deps_tag%:*}@$digest" >&2
fi
echo "$digest"
