#!/bin/bash -ex

source "$(dirname "$0")/ci-lib.sh"

trap 'echo "Termination signal received. Exiting..."; exit 1' SIGTERM SIGINT

log_disk_usage() {
  local label="${1:-}"
  echo "=== Disk usage ($label) ==="
  df -h / || true
  df -i / || true

  echo "--- Docker ---"
  docker system df || true
  docker ps -a || true
  docker volume ls || true

  echo "--- Workspace build dirs ---"
  du -sh */build 2>/dev/null || true

  echo "--- Temp dirs ---"
  du -sh /tmp 2>/dev/null || true

  echo "--- Gradle ---"
  du -sh "$GRADLE_USER_HOME/caches" 2>/dev/null || du -sh "$HOME/.gradle/caches" 2>/dev/null || true

  echo "=== End disk usage ==="
}

cleanup_docker_and_build() {
  # Exclude the container this script is running inside (prevents self-removal in DinD setups).
  # In Azure Pipelines container jobs, `hostname` is the short container ID used by `docker ps -aq`.
  local SELF_ID
  SELF_ID=$(hostname 2>/dev/null || true)
  local containers_to_remove
  if [ -n "$SELF_ID" ]; then
    containers_to_remove=$(docker ps -aq | grep -v "^${SELF_ID}" || true)
  else
    containers_to_remove=$(docker ps -aq || true)
  fi
  [ -n "$containers_to_remove" ] && echo "$containers_to_remove" | xargs docker rm -f || true
  docker builder prune -af || true
  docker system prune -af --volumes || true
  find . -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
}

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION; task? $ROR_TASK"

# Log file friendly Gradle output
export TERM=dumb

if [[ $ROR_TASK == "license_check" ]]; then
  echo ">>> Check all license headers are in place"
  ./gradlew --no-daemon license
fi

if [[ $ROR_TASK == "cve_check" ]]; then
  echo ">>> Running CVE checks.."
  ./gradlew --no-daemon dependencyCheckAnalyze
fi

if [[ $ROR_TASK == "compile_codebase_check" ]]; then
  echo ">>> Running compile codebase.."
  ./gradlew --no-daemon classes
fi

if [[ $ROR_TASK == "audit_build_check" ]]; then
  echo ">>> Running audit module cross build.."
  ./gradlew --no-daemon --stacktrace audit:crossBuildAssemble
fi

if [[ $ROR_TASK == "core_tests" ]]; then
  echo ">>> Running unit tests.."
  ./gradlew --no-daemon --stacktrace core:test audit:test
fi

run_integration_tests() {
  if [ "$#" -ne 1 ]; then
    echo "What ES module should I run integration tests for?"
    return 1
  fi

  ES_MODULE=$1
  local gradleArgs=("--no-daemon" "ror-tools:test" "integration-tests:test" "-PesModule=$ES_MODULE")
  [ -n "$ES_VERSION" ] && gradleArgs+=("-PesVersion=$ES_VERSION")

  echo ">>> $ES_MODULE => Running integration tests.."
  ./gradlew "${gradleArgs[@]}" || (find . | grep hs_err | xargs cat && exit 1)
}

if [[ $ROR_TASK == "integration_es94x" ]]; then
  run_integration_tests "es94x"
fi

if [[ $ROR_TASK == "integration_es92x" ]]; then
  run_integration_tests "es92x"
fi

if [[ $ROR_TASK == "integration_es91x" ]]; then
  run_integration_tests "es91x"
fi

if [[ $ROR_TASK == "integration_es90x" ]]; then
  run_integration_tests "es90x"
fi

if [[ $ROR_TASK == "integration_es818x" ]]; then
  run_integration_tests "es818x"
fi

if [[ $ROR_TASK == "integration_es816x" ]]; then
  run_integration_tests "es816x"
fi

if [[ $ROR_TASK == "integration_es815x" ]]; then
  run_integration_tests "es815x"
fi

if [[ $ROR_TASK == "integration_es814x" ]]; then
  run_integration_tests "es814x"
fi

if [[ $ROR_TASK == "integration_es813x" ]]; then
  run_integration_tests "es813x"
fi

if [[ $ROR_TASK == "integration_es812x" ]]; then
  run_integration_tests "es812x"
fi

if [[ $ROR_TASK == "integration_es811x" ]]; then
  run_integration_tests "es811x"
fi

if [[ $ROR_TASK == "integration_es810x" ]]; then
  run_integration_tests "es810x"
fi

if [[ $ROR_TASK == "integration_es89x" ]]; then
  run_integration_tests "es89x"
fi

if [[ $ROR_TASK == "integration_es88x" ]]; then
  run_integration_tests "es88x"
fi

if [[ $ROR_TASK == "integration_es87x" ]]; then
  run_integration_tests "es87x"
fi

if [[ $ROR_TASK == "integration_es85x" ]]; then
  run_integration_tests "es85x"
fi

if [[ $ROR_TASK == "integration_es84x" ]]; then
  run_integration_tests "es84x"
fi

if [[ $ROR_TASK == "integration_es83x" ]]; then
  run_integration_tests "es83x"
fi

if [[ $ROR_TASK == "integration_es82x" ]]; then
  run_integration_tests "es82x"
fi

if [[ $ROR_TASK == "integration_es81x" ]]; then
  run_integration_tests "es81x"
fi

if [[ $ROR_TASK == "integration_es80x" ]]; then
  run_integration_tests "es80x"
fi

if [[ $ROR_TASK == "integration_es717x" ]]; then
  run_integration_tests "es717x"
fi

if [[ $ROR_TASK == "integration_es716x" ]]; then
  run_integration_tests "es716x"
fi

if [[ $ROR_TASK == "integration_es714x" ]]; then
  run_integration_tests "es714x"
fi

if [[ $ROR_TASK == "integration_es711x" ]]; then
  run_integration_tests "es711x"
fi

if [[ $ROR_TASK == "integration_es710x" ]]; then
  run_integration_tests "es710x"
fi

if [[ $ROR_TASK == "integration_es79x" ]]; then
  run_integration_tests "es79x"
fi

if [[ $ROR_TASK == "integration_es78x" ]]; then
  run_integration_tests "es78x"
fi

if [[ $ROR_TASK == "integration_es77x" ]]; then
  run_integration_tests "es77x"
fi

if [[ $ROR_TASK == "integration_es74x" ]]; then
  run_integration_tests "es74x"
fi

if [[ $ROR_TASK == "integration_es73x" ]]; then
  run_integration_tests "es73x"
fi

if [[ $ROR_TASK == "integration_es72x" ]]; then
  run_integration_tests "es72x"
fi

if [[ $ROR_TASK == "integration_es70x" ]]; then
  run_integration_tests "es70x"
fi

if [[ $ROR_TASK == "integration_es67x" ]]; then
  run_integration_tests "es67x"
fi

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I build plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1

  while IFS= read -r version || [[ -n "$version" ]]; do
    time build_ror_plugin "$version"
  done <"$ROR_VERSIONS_FILE"
}

build_ror_plugin() {
  if [ "$#" -ne 1 ]; then
    echo "What ES version should I build plugin for?"
    return 1
  fi

  local ROR_VERSION=$1

  echo ""
  echo "Building ROR for ES $ROR_VERSION:"
  ./gradlew buildRorPlugin "-PesVersion=$ROR_VERSION" </dev/null
}

if [[ $ROR_TASK == "build_es9xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ $ROR_TASK == "build_es8xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ $ROR_TASK == "build_es7xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ $ROR_TASK == "build_es6xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es6x.txt"
fi

# ----------------------------------------------------------------------------------------------------
# Module-grouped publishing ("build once per module, repackage the rest").
#
# Within an esXXx module the compiled bytecode is identical across the module's patch range, so instead
# of recompiling for every ES version we compile ONCE (at the module's latestSupportedEsVersion) and
# derive every other version's zip by swapping only the per-version bits (descriptor, in-jar build-info,
# and the ES-version-stamped dependency jars). See ci/repackage-plugin-for-version.sh and the Gradle
# stageEsStampedJars task. A per-module bytecode guard (ci/verify-reusable-bytecode.sh) proves the reuse
# is safe before any release is published.
# ----------------------------------------------------------------------------------------------------

# Reads a generation's version file and emits one line per module (in file order):
#   "<module> <moduleLatest> <target version> [<target version> ...]"
build_module_groups() {
  local versions_file=$1
  local map_file
  map_file=$(mktemp)
  ./gradlew --no-daemon printEsModuleMap "-PversionsFile=$versions_file" "-PoutputFile=$map_file" </dev/null >/dev/null
  awk '
    { ver=$1; mod=$2; latest=$3
      if (!(mod in seen)) { order[++n]=mod; seen[mod]=1; modlatest[mod]=latest }
      vers[mod]=vers[mod] (vers[mod]=="" ? "" : " ") ver }
    END { for (i=1;i<=n;i++){ m=order[i]; print m" "modlatest[m]" "vers[m] } }
  ' "$map_file"
  rm -f "$map_file"
}

# Builds a module's base once, optionally guards bytecode reuse (release), then repackages + publishes
# every target version of that module from the base (no recompile).
#   $1 mode (upload_pre|release)  $2 ror_version  $3 module  $4 base_version
#   $5 stage_root  $6 out_dir  $7 stash_dir  $@(8..) target versions (file order: newest..oldest)
publish_module_group() {
  local mode=$1 ror_version=$2 module=$3 base_version=$4 stage_root=$5 out_dir=$6 stash_dir=$7
  shift 7
  local targets=("$@")
  local stage_csv
  stage_csv=$(IFS=,; echo "${targets[*]}")

  echo ""
  echo ">>> Module $module: base ES $base_version, ${#targets[@]} target version(s): ${targets[*]}"

  # 1) ONE compile for the module + stage the ES-version-stamped jars for all of its target versions.
  if ! ./gradlew --no-daemon ":${module}:buildRorPluginZip" ":${module}:stageEsStampedJars" \
        "-PesVersion=${base_version}" "-PstageVersions=${stage_csv}" "-PstageDir=${stage_root}" </dev/null; then
    echo "ERROR: base build/stage failed for $module @ $base_version"
    return 1
  fi

  local base_zip="${module}/build/distributions/readonlyrest-${ror_version}_es${base_version}.zip"
  local base_fat_jar="${module}/build/libs/readonlyrest-${ror_version}_es${base_version}.jar"

  # Stash the base artifacts: the guard recompiles (clobbering build/), so repackaging must read the
  # base from a stable location.
  local stashed_zip="${stash_dir}/$(basename "$base_zip")"
  cp "$base_zip" "$stashed_zip"

  # 2) (release only) Bytecode reuse guard at the range boundaries that differ from the base compile.
  if [ "$mode" = "release" ]; then
    local newest="${targets[0]}" oldest="${targets[$((${#targets[@]} - 1))]}"
    local guard_versions=()
    [ "$oldest" != "$base_version" ] && guard_versions+=("$oldest")
    [ "$newest" != "$base_version" ] && [ "$newest" != "$oldest" ] && guard_versions+=("$newest")
    if [ "${#guard_versions[@]}" -gt 0 ]; then
      if ! ci/verify-reusable-bytecode.sh "$module" "$base_fat_jar" "${guard_versions[@]}"; then
        echo "ERROR: bytecode reuse guard failed for $module"
        return 1
      fi
    fi
  fi

  # 3) Derive + publish each target version from the (stashed) base. No recompile.
  local target
  for target in "${targets[@]}"; do
    if ! ci/repackage-plugin-for-version.sh "$stashed_zip" "$target" "$ror_version" "$stage_root" "$out_dir"; then
      echo "ERROR: repackage failed for $module ES $target"
      return 1
    fi
    local zip="${out_dir}/readonlyrest-${ror_version}_es${target}.zip"

    if ! ci/upload-files-to-s3.sh "$zip" "${zip}.sha512" "${zip}.sha1" "${ror_version}/"; then
      echo "ERROR: S3 upload failed for $module ES $target"
      return 1
    fi

    if [ "$mode" = "release" ]; then
      if ! release_docker_and_tag "$ror_version" "$target" "$module" "$zip"; then
        return 1
      fi
    fi

    rm -f "$zip" "${zip}.sha1" "${zip}.sha512"
  done

  # 4) Bound disk at the MODULE boundary (not per version).
  rm -f "$stashed_zip"
  rm -rf "${stage_root:?}/"*
  find "$module" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
  return 0
}

# Release-only: build & push the ES+ROR image for one version from the already-derived zip (so the image
# build does NOT recompile), then create the git tag. Mirrors the original release_ror_plugin tail.
release_docker_and_tag() {
  local ror_version=$1 es_version=$2 module=$3 repackaged_zip=$4
  local TAG="v${ror_version}_es${es_version}"

  if ! checkTagNotExist "$TAG"; then
    return 0
  fi

  if docker manifest inspect "docker.elastic.co/elasticsearch/elasticsearch:${es_version}" >/dev/null 2>&1; then
    if ! ./gradlew ":${module}:pushRorDockerImage" "-PesVersion=$es_version" "-PrepackagedZip=$(cd "$(dirname "$repackaged_zip")" && pwd)/$(basename "$repackaged_zip")" </dev/null; then
      echo "Failed to publish plugin Docker image for ES $es_version"
      return 4
    fi
  else
    echo "WARN: Skipping ES+ROR image for $es_version (no Elasticsearch base image in registry)"
  fi

  tag "$TAG"
}

# Drives a whole generation file through the module-grouped flow, with a per-module retry.
publish_ror_plugins_grouped() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: publish_ror_plugins_grouped <versions file> <upload_pre|release>"
    return 1
  fi
  local versions_file=$1 mode=$2
  local ror_version
  ror_version=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  # Pin SOURCE_DATE_EPOCH for the whole run so BuildKit normalises every build-time timestamp (file
  # mtimes + the parent dirs `COPY --link` synthesises) to a constant. This is what makes the
  # version-independent fat ROR docker layer keep ONE content digest across every patch version, so the
  # registry stores/pushes that ~95 MB blob once per module instead of once per version. The Gradle push
  # tasks read this env (with a constant fallback); exporting the commit time here just gives the images a
  # meaningful, reproducible "created" date. Must be set before the first gradlew call so the daemon (if
  # any) inherits it.
  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1704067200)}"

  local stage_root out_dir stash_dir
  stage_root=$(mktemp -d); out_dir=$(mktemp -d); stash_dir=$(mktemp -d)

  local groups
  groups=$(build_module_groups "$versions_file")

  local line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    local module base_version
    module=$(echo "$line" | awk '{print $1}')
    base_version=$(echo "$line" | awk '{print $2}')
    local targets
    read -r -a targets <<< "$(echo "$line" | cut -d' ' -f3-)"

    local attempt
    for attempt in 1 2 3; do
      if time publish_module_group "$mode" "$ror_version" "$module" "$base_version" "$stage_root" "$out_dir" "$stash_dir" "${targets[@]}"; then
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "WARN: module $module failed (attempt $attempt/3), retrying after cleanup..."
        log_disk_usage "before retry cleanup ($module attempt $attempt)"
        cleanup_docker_and_build
        log_disk_usage "after retry cleanup ($module attempt $attempt)"
      else
        echo "ERROR: module $module failed after 3 attempts"
        rm -rf "$stage_root" "$out_dir" "$stash_dir"
        return 1
      fi
    done
  done <<< "$groups"

  rm -rf "$stage_root" "$out_dir" "$stash_dir"
}

upload_pre_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I upload pre-plugins for?"
    return 1
  fi
  publish_ror_plugins_grouped "$1" "upload_pre"
}

if [[ $ROR_TASK == "upload_pre_es9xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ $ROR_TASK == "upload_pre_es8xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ $ROR_TASK == "upload_pre_es7xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ $ROR_TASK == "upload_pre_es6xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es6x.txt"
fi

release_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I release plugins for?"
    return 1
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  publish_ror_plugins_grouped "$1" "release"
}

if [[ $ROR_TASK == "release_es9xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ $ROR_TASK == "release_es8xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ $ROR_TASK == "release_es7xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ $ROR_TASK == "release_es6xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es6x.txt"
fi

check_maven_artifacts_exist() {
  local CURRENT_VERSION="$1"

  local ARTIFACT_URL="https://repo1.maven.org/maven2/tech/beshu/ror/audit_3/$CURRENT_VERSION/"
  echo ">>> Checking if Maven artifacts already exist at: $ARTIFACT_URL"
  
  local MVN_STATUS=$(curl -L --write-out '%{http_code}' --silent --output /dev/null "$ARTIFACT_URL" || echo "000")
  
  if [[ $MVN_STATUS == "404" ]]; then
    echo ">>> Maven artifacts not found"
    return 1
  elif [[ $MVN_STATUS == "200" ]]; then
    echo ">>> Maven artifacts for version $CURRENT_VERSION already exist."
    return 0
  else
    echo ">>> ERROR: Unexpected HTTP status $MVN_STATUS when checking Maven repository"
    echo ">>> Cannot determine if artifacts exist, failing to avoid potential issues"
    exit 1
  fi
}

if [[ $ROR_TASK == "publish_maven_artifacts" ]]; then
  # .travis/secret.pgp is downloaded via Azure secret files, see azure-pipelines.yml
  CURRENT_PLUGIN_VER=$(awk -F= '$1=="pluginVersion" {print $2}' gradle.properties)
  PUBLISHED_PLUGIN_VER=$(awk -F= '$1=="publishedPluginVersion" {print $2}' gradle.properties)

  if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
    if check_maven_artifacts_exist "$CURRENT_PLUGIN_VER"; then
      echo ">>> Skipping publishing audit module artifacts"
    else
      echo ">>> Publishing audit module artifacts to sonatype repo"
      ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
    fi
  else
    echo ">>> Version mismatch: current=$CURRENT_PLUGIN_VER, published=$PUBLISHED_PLUGIN_VER"
    echo ">>> Skipping publishing audit module artifacts."
  fi
fi

if [[ $ROR_TASK == "publish_pre_builds_docker_images" ]]; then

  if [ -z "$(echo "$BUILD_ROR_ES_VERSIONS" | tr -d '[:space:],')" ]; then
    echo "Error: BUILD_ROR_ES_VERSIONS is required"
    exit 1
  fi

  # IMAGE_TAG is optional; its pipeline default is a single space, so normalize whitespace-only to empty.
  IMAGE_TAG="$(echo "${IMAGE_TAG:-}" | tr -d '[:space:]')"

  IFS=', ' read -r -a VERSIONS <<< "$BUILD_ROR_ES_VERSIONS"
  for VERSION in "${VERSIONS[@]}"; do
    if [ -n "$VERSION" ]; then
      publish_ror_prebuild_plugin "$VERSION" "$IMAGE_TAG"
      docker system prune -fa
    fi
  done

fi
