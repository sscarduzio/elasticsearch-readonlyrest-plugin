#!/bin/bash
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

# Derives the ROR plugin zip for a target ES patch version from a module's already-built BASE zip,
# WITHOUT recompiling. Within an esXXx module the compiled bytecode is identical across the module's
# patch range; only a small, enumerable set differs per version:
#   * plugin-descriptor.properties   -> elasticsearch.version (ES-enforced to equal the node version)
#   * ror-build-info.properties      -> es_version (lives inside the fat ROR jar; boot-log only)
#   * the ES-version-stamped dependency jars (elasticsearch-rest-client-<ver>.jar always; for es6x/es7x
#     also transport-netty4-client-<ver>.jar) -- staged for the target version by the Gradle
#     `stageEsStampedJars` task into <stageDir>/<target>/.
# Everything else in the zip is fixed-version and reused as-is from the base.
#
# Usage:
#   ci/repackage-plugin-for-version.sh <baseZip> <targetEsVersion> <pluginVersion> <stageDir> <outputDir>
#
# Produces in <outputDir>:
#   readonlyrest-<pluginVersion>_es<targetEsVersion>.zip
#   readonlyrest-<pluginVersion>_es<targetEsVersion>.zip.sha1
#   readonlyrest-<pluginVersion>_es<targetEsVersion>.zip.sha512

set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "Usage: $0 <baseZip> <targetEsVersion> <pluginVersion> <stageDir> <outputDir>" >&2
  exit 1
fi

BASE_ZIP="$1"
TARGET_ES_VERSION="$2"
PLUGIN_VERSION="$3"
STAGE_DIR="$4"
OUTPUT_DIR="$5"

if [ ! -f "$BASE_ZIP" ]; then
  echo "ERROR: base zip not found: $BASE_ZIP" >&2
  exit 1
fi

TARGET_STAGE_DIR="${STAGE_DIR%/}/${TARGET_ES_VERSION}"
if [ ! -d "$TARGET_STAGE_DIR" ]; then
  echo "ERROR: staged ES jars dir not found for ES ${TARGET_ES_VERSION}: $TARGET_STAGE_DIR" >&2
  echo "       (run the Gradle 'stageEsStampedJars' task for this module's versions first)" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
BASE_ZIP="$(cd "$(dirname "$BASE_ZIP")" && pwd)/$(basename "$BASE_ZIP")"
TARGET_STAGE_DIR="$(cd "$TARGET_STAGE_DIR" && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

unzip -qo "$BASE_ZIP" -d "$WORK_DIR"

# Base ES version, read from the base zip's descriptor (authoritative -- avoids parsing file names).
BASE_ES_VERSION="$(grep -E '^elasticsearch\.version=' "$WORK_DIR/plugin-descriptor.properties" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
if [ -z "$BASE_ES_VERSION" ]; then
  echo "ERROR: could not read elasticsearch.version from base descriptor" >&2
  exit 1
fi

echo "Repackaging ${BASE_ES_VERSION} base -> ES ${TARGET_ES_VERSION} (ROR ${PLUGIN_VERSION})"

# 1) plugin-descriptor.properties: only elasticsearch.version is per-version (the `version` field is the
#    plain plugin version, unchanged across patch versions).
DESCRIPTOR="$WORK_DIR/plugin-descriptor.properties"
TMP_DESCRIPTOR="$(mktemp)"
sed -E "s/^elasticsearch\.version=.*/elasticsearch.version=${TARGET_ES_VERSION}/" "$DESCRIPTOR" > "$TMP_DESCRIPTOR"
mv "$TMP_DESCRIPTOR" "$DESCRIPTOR"

# 2) ror-build-info.properties inside the fat ROR jar (boot-log only). Update the single entry in place
#    and rename the jar to carry the target version (cosmetic -- ES scans the dir, the jar name is not
#    load-bearing).
FAT_JAR_BASE="readonlyrest-${PLUGIN_VERSION}_es${BASE_ES_VERSION}.jar"
FAT_JAR_TARGET="readonlyrest-${PLUGIN_VERSION}_es${TARGET_ES_VERSION}.jar"
if [ ! -f "$WORK_DIR/$FAT_JAR_BASE" ]; then
  echo "ERROR: fat ROR jar not found in base zip: $FAT_JAR_BASE" >&2
  exit 1
fi
(
  cd "$WORK_DIR"
  printf 'es_version=%s\nplugin_version=%s' "$TARGET_ES_VERSION" "$PLUGIN_VERSION" > ror-build-info.properties
  zip -Xq "$FAT_JAR_BASE" ror-build-info.properties
  rm -f ror-build-info.properties
  if [ "$FAT_JAR_BASE" != "$FAT_JAR_TARGET" ]; then
    mv "$FAT_JAR_BASE" "$FAT_JAR_TARGET"
  fi
)

# 3) Swap the ES-version-stamped dependency jars: drop in the staged <target> jars and remove their
#    <base> counterparts. The staged file name is "<artifact>-<target>.jar"; the base zip carries
#    "<artifact>-<base>.jar".
shopt -s nullglob
staged_jars=("$TARGET_STAGE_DIR"/*.jar)
shopt -u nullglob
if [ "${#staged_jars[@]}" -eq 0 ]; then
  echo "ERROR: no staged ES jars in $TARGET_STAGE_DIR" >&2
  exit 1
fi
for staged in "${staged_jars[@]}"; do
  staged_name="$(basename "$staged")"
  artifact="${staged_name%-${TARGET_ES_VERSION}.jar}"
  if [ "$artifact" = "$staged_name" ]; then
    echo "ERROR: staged jar '$staged_name' does not match expected '<artifact>-${TARGET_ES_VERSION}.jar'" >&2
    exit 1
  fi
  rm -f "$WORK_DIR/${artifact}-${BASE_ES_VERSION}.jar"
  cp "$staged" "$WORK_DIR/$staged_name"
done

# 4) Re-zip flat (entries at the archive root, matching the original packageRorPlugin layout).
OUTPUT_ZIP="$OUTPUT_DIR/readonlyrest-${PLUGIN_VERSION}_es${TARGET_ES_VERSION}.zip"
rm -f "$OUTPUT_ZIP"
(
  cd "$WORK_DIR"
  zip -Xrq "$OUTPUT_ZIP" .
)

# 5) Checksums -- bare hex digests, matching createRorPluginChecksums (.sha512) and the Zip doLast ant
#    sha1 (.sha1) the normal build emits.
if command -v sha1sum >/dev/null 2>&1; then
  sha1sum "$OUTPUT_ZIP" | cut -d' ' -f1 > "${OUTPUT_ZIP}.sha1"
  sha512sum "$OUTPUT_ZIP" | cut -d' ' -f1 > "${OUTPUT_ZIP}.sha512"
else
  shasum -a 1 "$OUTPUT_ZIP" | cut -d' ' -f1 > "${OUTPUT_ZIP}.sha1"
  shasum -a 512 "$OUTPUT_ZIP" | cut -d' ' -f1 > "${OUTPUT_ZIP}.sha512"
fi

echo "Wrote $OUTPUT_ZIP (+ .sha1 / .sha512)"
