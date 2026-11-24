#!/usr/bin/env bash
# Makes sure the toolchains image of this commit exists, and prints its name and digest:
#   image=<repository>:<tag>  digest=sha256:...
# The tag comes from ci/toolchains/image-tag.sh. A missing tag is built and pushed when the job gives
# Docker Hub push credentials (DOCKER_REGISTRY_USER / DOCKER_REGISTRY_PASSWORD). A job without them
# (a fork pull request) takes TOOLCHAINS_IMAGE of image.env, and Gradle downloads what that image lacks.
# PUSH_BASE_TAG=true also moves TOOLCHAINS_IMAGE to the new build (a develop push).
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env
# shellcheck source=ci/log.sh
source ci/log.sh

tag=$(ci/toolchains/image-tag.sh)
digest=$(ci/toolchains/registry-digest.sh "$tag") || { ci_log "Docker Hub cannot say whether $tag exists."; exit 1; }

if [ -z "$digest" ]; then
  if [ -n "${DOCKER_REGISTRY_USER:-}" ] && [ -n "${DOCKER_REGISTRY_PASSWORD:-}" ]; then
    ci_log "No image $tag. Building it."
    # shellcheck source=ci/configure-docker.sh
    source ci/configure-docker.sh >&2
    tags=("$tag")
    [ "${PUSH_BASE_TAG:-false}" = "true" ] && tags+=("$TOOLCHAINS_IMAGE")
    digest=$(ci/toolchains/build-image.sh "${tags[@]}")
  else
    ci_log "No image $tag, and no push credentials to build it. This run takes $TOOLCHAINS_IMAGE."
    tag=$TOOLCHAINS_IMAGE
    digest=$(ci/toolchains/registry-digest.sh "$tag") || { ci_log "Docker Hub cannot say whether $tag exists."; exit 1; }
    [ -n "$digest" ] || { ci_log "No image $tag."; exit 1; }
  fi
fi
echo "image=$tag"
echo "digest=$digest"
