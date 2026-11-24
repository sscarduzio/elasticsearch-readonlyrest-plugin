#!/usr/bin/env bash
# Prints the toolchains image tag of this commit: the TOOLCHAINS_IMAGE of image.env + a hash of the
# files that decide the baked Gradle dependencies. The plugin version lines are left out of the hash.
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env

hash=$(find . \( -path ./.git -o -name build -o -name .gradle \) -prune -o -type f \
         \( -name '*.gradle' -o -name gradle.properties -o -name gradle-wrapper.properties \
            -o -path './build-base/src/main/*' -o -path './ci/toolchains/*' \) -print0 |
       LC_ALL=C sort -z |
       while IFS= read -r -d '' f; do
         printf '%s\n' "$f"
         grep -v -E '^(published)?[pP]luginVersion=' "$f" || true
       done | sha256sum | cut -c1-16)
echo "${TOOLCHAINS_IMAGE}-deps-${hash}"
