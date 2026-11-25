#!/usr/bin/env bash
# Selects the toolchains image of this run. It needs no credentials: registry-digest.sh reads anonymously.
# Prints:  image=<repository>:<tag>  digest=sha256:...  deps_tag=<prefix>-deps-<hash>  deps_missing=true|false
# The image is the deps tag of this commit. When that tag is missing, it is <prefix>-develop: Gradle then
# downloads only the changed jars, and a separate job builds the deps tag for the next run.
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env
# shellcheck source=ci/log.sh
source ci/log.sh

deps_tag=$(ci/toolchains/deps-tag.sh)
image=$deps_tag missing=false
digest=$(ci/toolchains/registry-digest.sh "$image") || { ci_log "Docker Hub cannot say whether $image exists."; exit 1; }
if [ -z "$digest" ]; then
  image="${TOOLCHAINS_TAG_PREFIX}-develop" missing=true
  ci_log "No image $deps_tag. This run takes $image."
  digest=$(ci/toolchains/registry-digest.sh "$image") || { ci_log "Docker Hub cannot say whether $image exists."; exit 1; }
  [ -n "$digest" ] || { ci_log "No image $image."; exit 1; }
fi
echo "image=$image"
echo "digest=$digest"
echo "deps_tag=$deps_tag"
echo "deps_missing=$missing"
