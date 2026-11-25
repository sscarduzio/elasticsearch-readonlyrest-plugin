#!/usr/bin/env bash
# Prints <prefix>-deps-<hash>: the toolchains image tag of the dependencies of this commit's build files.
# Each new hash costs an image build, so the hash reads only the files that decide the baked dependencies:
# *.gradle, es*/gradle.properties (the other gradle.properties files hold no dependency data),
# gradle-wrapper.properties, build-base/src/main and the image build files. Comment and blank lines are left out.
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env

hash=$(find . \( -path ./.git -o -name build -o -name .gradle \) -prune -o -type f \
         \( -name '*.gradle' -o -path './es*/gradle.properties' -o -name gradle-wrapper.properties \
            -o -path './build-base/src/main/*' -o -name 'JdkToolchains.Dockerfile*' \) -print0 |
       LC_ALL=C sort -z |
       while IFS= read -r -d '' f; do
         printf '%s\n' "$f"
         grep -v -E '^[[:space:]]*(#|//|$)' "$f" || true
       done | sha256sum | cut -c1-16)
echo "${TOOLCHAINS_TAG_PREFIX}-deps-${hash}"
