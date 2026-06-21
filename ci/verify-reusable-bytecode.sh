#
#    This file is part of ReadonlyREST.
#
#    ReadonlyREST is free software: you can redistribute it and/or modify
#    it under the terms of the GNU General Public License as published by
#    the Free Software Foundation, either version 3 of the License, or
#    (at your option) any later version.
#
#    ReadonlyREST is distributed in the hope that it will be useful,
#    but WITHOUT ANY WARRANTY; without even the implied warranty of
#    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#    GNU General Public License for more details.
#
#    You should have received a copy of the GNU General Public License
#    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
#

#!/bin/bash

# Boundary-diff guard for the "build once per module, repackage the rest" jar optimization.
#
# Repackaging ships the module's base (module-latest) compiled bytecode under every patch version's
# label. That is only safe if the adapter compiles to IDENTICAL bytecode across the module's whole patch
# range. This script proves it: it recompiles the module's fat jar at one or more boundary versions
# (typically the oldest and the newest ES version the generation file routes to the module) and diffs
# the class/resource bytecode against the base jar. Identical => reuse is safe. Any difference (or a
# compile failure at an older version) => the range is NOT API-monotonic; FAIL so the operator reverts
# that module to per-version compilation.
#
# Entries excluded from the diff: ror-build-info.properties (intentionally per-version, boot-log only)
# and META-INF/MANIFEST.MF (build timestamp noise).
#
# Usage:
#   ci/verify-reusable-bytecode.sh <module> <baseFatJar> <cmpEsVersion> [<cmpEsVersion> ...]

set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 <module> <baseFatJar> <cmpEsVersion> [<cmpEsVersion> ...]" >&2
  exit 1
fi

MODULE="$1"
BASE_FAT_JAR="$2"
shift 2
CMP_VERSIONS=("$@")

if [ ! -f "$BASE_FAT_JAR" ]; then
  echo "ERROR: base fat jar not found: $BASE_FAT_JAR" >&2
  exit 1
fi
BASE_FAT_JAR="$(cd "$(dirname "$BASE_FAT_JAR")" && pwd)/$(basename "$BASE_FAT_JAR")"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# Digest of a jar's comparable entries: every entry except the intentionally-varying ones, as a sorted
# "<sha256>  <entry>" list.
digest_jar() {
  local jar="$1" out="$2" tmp
  tmp="$(mktemp -d)"
  unzip -qo "$jar" -d "$tmp"
  rm -f "$tmp/ror-build-info.properties" "$tmp/META-INF/MANIFEST.MF"
  ( cd "$tmp" && find . -type f -exec shasum -a 256 {} \; | sort -k2 ) > "$out"
  rm -rf "$tmp"
}

BASE_DIGEST="$(mktemp)"
digest_jar "$BASE_FAT_JAR" "$BASE_DIGEST"

failed=0
for cmp in "${CMP_VERSIONS[@]}"; do
  echo "==> Compiling ${MODULE} at ES ${cmp} for bytecode comparison ..."
  ./gradlew --no-daemon ":${MODULE}:toJar" "-PesVersion=${cmp}"

  cmp_jar="$(ls -1 "${MODULE}"/build/libs/readonlyrest-*_es"${cmp}".jar 2>/dev/null | head -1 || true)"
  if [ -z "$cmp_jar" ] || [ ! -f "$cmp_jar" ]; then
    echo "FAIL [$MODULE @ $cmp]: expected fat jar not produced (compile against ES $cmp failed?)" >&2
    failed=1
    continue
  fi

  cmp_digest="$(mktemp)"
  digest_jar "$cmp_jar" "$cmp_digest"

  if diff -q <(awk '{print $1"  "$2}' "$BASE_DIGEST") <(awk '{print $1"  "$2}' "$cmp_digest") >/dev/null; then
    echo "OK   [$MODULE @ $cmp]: bytecode identical to base -- reuse safe."
  else
    echo "FAIL [$MODULE @ $cmp]: bytecode DIFFERS from base. Diverging entries:" >&2
    diff <(awk '{print $2}' "$BASE_DIGEST") <(awk '{print $2}' "$cmp_digest") | grep '^[<>]' || true
    comm -3 \
      <(sort "$BASE_DIGEST") <(sort "$cmp_digest") \
      | awk '{print $NF}' | sort -u >&2
    failed=1
  fi
  rm -f "$cmp_digest"
done

rm -f "$BASE_DIGEST"

if [ "$failed" -ne 0 ]; then
  echo "Bytecode reuse guard FAILED for module ${MODULE}. Repackaging is unsafe for this range; revert ${MODULE} to per-version compilation." >&2
  exit 1
fi
echo "Bytecode reuse guard passed for module ${MODULE}."
