#!/bin/bash -ex

source "$(dirname "$0")/ci-lib.sh"

# On cancel/timeout (SIGTERM) kill the gradle process group + reap this CI job's containers, else they
# orphan (scoped by ror.ci-job=$ROR_CI_JOB_ID so a sibling CI job on the shared daemon is untouched).

# PIDs of gradle group leaders (1 per shard); an ARRAY not a string (find-replace pruning could
# corrupt `123` vs `1234`). Never pruned — kill on a dead PID is a no-op.
GRADLE_PIDS=()
terminate() {
  echo ">>> Termination signal received — killing gradle tree(s) + reaping this CI job's containers..."
  # Negative PID = signal the whole process group (gradle + its worker JVMs).
  for pid in "${GRADLE_PIDS[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  sleep 5
  for pid in "${GRADLE_PIDS[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
  reap_ci_job_containers
  exit 1
}
trap terminate SIGTERM SIGINT

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

if [[ $ROR_TASK == "format_code_check" ]]; then
  echo ">>> Running format check..."
  ./gradlew --no-daemon formatCodeCheck
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
  local shardCount="${IT_PARALLELISM:-1}"
  local esArgs=("-PesModule=$ES_MODULE")
  [ -n "$ES_VERSION" ] && esArgs+=("-PesVersion=$ES_VERSION")

  echo ">>> $ES_MODULE => ror-tools:test (serial gate) + integration-tests in ${shardCount} shard(s).."

  # Each gradle invocation runs in its OWN process group (setsid) so the trap can reap the whole tree;
  # appends the leader PID to GRADLE_PIDS (never pruned) and sets LAST_PID for the caller.

  # Do NOT give shards separate --project-cache-dirs: they'd rebuild shared :build-base concurrently
  # and race (':build-base:generatePluginAdapters FAILED', 10502). Shared cache lets file-locking serialize it.
  LAST_PID=""
  run_one() {  # args: <gradle args...>
    setsid ./gradlew --no-daemon "$@" &
    LAST_PID=$!; GRADLE_PIDS+=("$LAST_PID")
  }

  # 1) ror-tools:test ONCE, serially (cheap, no ES; gates the CI job). Also warms :build-base/:buildSrc.
  run_one ror-tools:test
  wait "$LAST_PID"; local rc=$?
  if [ "$rc" -ne 0 ]; then find . | grep hs_err | xargs cat 2>/dev/null || true; return "$rc"; fi

  # 2) Build the singleton ES image + test classes ONCE before the parallel shards, so the K shards do
  #    NO shared build work — only run their disjoint test subset.
  run_one integration-tests:prebuildEsImage integration-tests:testClasses "${esArgs[@]}"
  wait "$LAST_PID"; rc=$?
  if [ "$rc" -ne 0 ]; then return "$rc"; fi

  # Disk: each distinct ES config builds a ~1GB+ ror-it-es:<hash> image. Do NOT prune mid-run — it races
  # concurrent builds and removes in-use layers ("unknown parent image ID", 10532); out of disk => bigger runner.

  # 3) integration-tests:test — K shards in PARALLEL (shared cache). Each is a separate JVM => its own
  #    ES container, running a disjoint suite subset (name-hash mod K, see build.gradle).
  local pids="" idx
  for idx in $(seq 0 $((shardCount - 1))); do
    run_one integration-tests:test "${esArgs[@]}" -PshardCount="$shardCount" -PshardIndex="$idx"
    pids="$pids $LAST_PID"
  done
  rc=0
  for p in $pids; do wait "$p" || rc=$?; done
  if [ "$rc" -ne 0 ]; then find . | grep hs_err | xargs cat 2>/dev/null || true; return "$rc"; fi
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

upload_pre_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I upload pre-plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1
  local ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  while IFS= read -r version; do
    time upload_pre_ror_plugin "$ROR_VERSION" "$version"
  done <"$ROR_VERSIONS_FILE"
}

upload_pre_ror_plugin() {
  if [ "$#" -ne 2 ]; then
    echo "What ES and ROR version should I upload pre-plugin for?"
    return 1
  fi

  local ROR_VERSION=$1
  local ES_VERSION=$2
  local TAG="v${ROR_VERSION}_es${ES_VERSION}"

  echo ""
  echo "Uploading pre-ROR $ROR_VERSION for ES $ES_VERSION:"

  ./gradlew publishRorPlugin "-PesVersion=$ES_VERSION" </dev/null
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

  local ROR_VERSIONS_FILE=$1
  local ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  while IFS= read -r version || [[ -n "$version" ]]; do
    local attempt
    for attempt in 1 2 3; do
      if time release_ror_plugin "$ROR_VERSION" "$version"; then
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "WARN: release_ror_plugin failed for ES $version (attempt $attempt/3), retrying after cleanup..."
        log_disk_usage "before retry cleanup (attempt $attempt)"
        cleanup_docker_and_build
        log_disk_usage "after retry cleanup (attempt $attempt)"
      else
        echo "ERROR: release_ror_plugin failed for ES $version after 3 attempts"
        return 1
      fi
    done
  done <"$ROR_VERSIONS_FILE"
}

release_ror_plugin() {
  if [ "$#" -ne 2 ]; then
    echo "What ES and ROR version should I release plugin for?"
    return 1
  fi

  local ROR_VERSION=$1
  local ES_VERSION=$2

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ES version format. Expected format: X.Y.Z"
    return 2
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  local TAG="v${ROR_VERSION}_es${ES_VERSION}"

  echo ""
  echo "Releasing ROR $ROR_VERSION for ES $ES_VERSION:"

  if checkTagNotExist "$TAG"; then

    if ! ./gradlew publishRorPlugin "-PesVersion=$ES_VERSION" </dev/null; then
      echo "Failed to publish plugin to S3"
      return 3
    fi

    if docker manifest inspect "docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}" >/dev/null 2>&1; then
      if ! ./gradlew publishEsRorDockerImage "-PesVersion=$ES_VERSION" </dev/null; then
        echo "Failed to publish plugin Docker image"
        return 4
      fi
    else
      echo "WARN: Skipping building and publishing Elasticsearch image with ROR installed because there was no Elasticsearch image for version: $ES_VERSION found in the docker registry"
    fi

    tag "$TAG"
  fi

  # Clean up after every version to prevent disk space exhaustion
  log_disk_usage "after release of ES $ES_VERSION"
  cleanup_docker_and_build
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
