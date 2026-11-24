#!/usr/bin/env bash
# Prints the Actions cache key of the Gradle read-only cache. Its inputs are the files that change the cache;
# the push paths of .github/workflows/fill-gradle-ro-cache.yml must list them.
set -euo pipefail
cd "$(dirname "$0")/.."

hash=$(find . \( -path ./.git -o -path ./.gradle-ro-cache -o -name build -o -name .gradle \) -prune -o -type f \
         \( -name '*.gradle' -o -name gradle.properties -o -name gradle-wrapper.properties \
            -o -path './build-base/src/main/*' \
            -o -path ./ci/fill-gradle-cache.sh -o -path ./ci/gradle-ro-cache-key.sh \) -print0 |
       LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16)
echo "gradle-ro-cache-${hash}"
