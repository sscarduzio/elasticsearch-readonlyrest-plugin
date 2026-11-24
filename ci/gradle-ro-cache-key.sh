#!/usr/bin/env bash
# Prints the Actions cache key of the Gradle read-only cache. It changes when a build file, a Gradle property
# or the Gradle version changes, because each of these can change the dependency set.
set -euo pipefail
cd "$(dirname "$0")/.."

hash=$(find . \( -path ./.git -o -path ./.gradle-ro-cache -o -name build -o -name .gradle \) -prune -o -type f \
         \( -name '*.gradle' -o -name gradle.properties -o -name gradle-wrapper.properties \
            -o -path './build-base/src/main/*' \) -print0 |
       LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16)
echo "gradle-ro-cache-${hash}"
