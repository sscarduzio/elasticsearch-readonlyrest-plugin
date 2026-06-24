#!/usr/bin/env bash
# OLD flow (pre-optimization, es84x reverted to master): compile + build the full image +
# push, independently for every version. No repackage, no layer dedup, Alpine+GPG gosu each time.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

M=es84x
ALL=(8.4.0 8.4.1 8.4.2)

./gradlew clean

for v in "${ALL[@]}"; do
  time ./gradlew ":$M:pushRorDockerImage" -PesVersion="$v"
done
