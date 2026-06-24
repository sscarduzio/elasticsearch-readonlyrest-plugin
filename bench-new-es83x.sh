#!/usr/bin/env bash
# NEW flow (optimized): compile the module ONCE, repackage the other versions (no recompile),
# then push all three release images reusing the zips. 3 versions of es83x.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

M=es83x
BASE=8.3.0
OTHERS=8.3.1,8.3.2
ALL=(8.3.0 8.3.1 8.3.2)
JARS=$(mktemp -d)

./gradlew clean

# 1) ONE compile (base version)
time ./gradlew ":$M:buildRorPluginZip" -PesVersion="$BASE"

# 2) derive the other versions WITHOUT recompiling
time ./gradlew ":$M:downloadEsVersionedDependencyJars" -PesVersion="$BASE" -PtargetVersions="$OTHERS" -PesJarsDir="$JARS"
time ./gradlew ":$M:repackageRorPluginForVersions"     -PesVersion="$BASE" -PtargetVersions="$OTHERS" -PesJarsDir="$JARS"

# 3) push all three, reusing the packaged zips (no recompile)
for v in "${ALL[@]}"; do
  time ./gradlew ":$M:pushRorDockerImage" -PreusePackagedZip -PesVersion="$v"
done

rm -rf "$JARS"
