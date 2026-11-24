#!/usr/bin/env bash
# Fills a Gradle read-only dependency cache with the dependencies that the toolchains image lacks.
# Usage: ci/fill-gradle-cache.sh <output dir>    (in the toolchains image; writes <output dir>/modules-2)
set -euo pipefail

OUT=${1:?Usage: $0 <output dir>}
BAKED=${GRADLE_USER_HOME:?GRADLE_USER_HOME is not set: run this in the toolchains image}
[ -d "$BAKED/caches/modules-2" ] || { echo "No baked Gradle cache in $BAKED." >&2; exit 1; }

# A separate Gradle home that reads the baked cache, so it downloads only what the image lacks.
FILL_HOME=$(mktemp -d)
trap 'rm -rf "$FILL_HOME"' EXIT
ln -s "$BAKED/wrapper" "$FILL_HOME/wrapper"
cp "$BAKED/gradle.properties" "$FILL_HOME/"

GRADLE_USER_HOME=$FILL_HOME GRADLE_RO_DEP_CACHE=$BAKED/caches \
  ./gradlew --no-daemon --init-script ci/toolchains/resolve-deps.init.gradle \
    resolveCiDependencies resolveEsVersionedDependencies

mkdir -p "$OUT"
rm -rf "$OUT/modules-2"
if [ -d "$FILL_HOME/caches/modules-2" ]; then
  mv "$FILL_HOME/caches/modules-2" "$OUT/"
  # Gradle refuses a read-only cache that holds lock files.
  find "$OUT/modules-2" \( -name '*.lock' -o -name 'gc.properties' \) -delete
else
  mkdir -p "$OUT/modules-2"
fi
echo "Gradle read-only cache in $OUT: $(du -sh "$OUT/modules-2" | cut -f1)."
