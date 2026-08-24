#!/bin/bash -ex

source "$(dirname "$0")/ci-lib.sh"
source "$(dirname "$0")/publish-ror-plugins.sh"
source "$(dirname "$0")/e2e-tests-lib.sh"

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

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION; task: $ROR_TASK"

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
  # Convert DEPENDENCY_CHECK_DATA_DIR to an absolute path before invoking Gradle.
  # H2 SHUTDOWN DEFRAG closes the DB during defrag; when dependency-check reopens
  # it in read-only mode for analysis, a relative path fails the isFile() check
  # because the CWD context is lost after H2 closes the connection.
  if [[ -n "$DEPENDENCY_CHECK_DATA_DIR" ]]; then
    export DEPENDENCY_CHECK_DATA_DIR="$(cd "$DEPENDENCY_CHECK_DATA_DIR" 2>/dev/null && pwd || echo "$(pwd)/$DEPENDENCY_CHECK_DATA_DIR")"
    echo "    DEPENDENCY_CHECK_DATA_DIR resolved to: $DEPENDENCY_CHECK_DATA_DIR"
  fi
  CVE_LOG="$(pwd)/build/cve-scan.log"
  CVE_MODE_FILE="$(pwd)/build/cve-scan-mode.txt"
  mkdir -p "$(dirname "$CVE_LOG")"

  # Do not add --parallel here. dependencyCheckAnalyze runs in all 41 subprojects that apply
  # readonlyrest.base-common-conventions, and those subprojects share one H2 data directory.
  # Only a sequential run is safe.
  #
  # -Danalyzer.ossindex.request.delay limits a retry loop. That loop exists because OSS Index
  # errors are now warn-only. See the ossIndex block in readonlyrest.base-common-conventions.gradle.
  # dependency-check does not treat a 429, or an unknown error, as fatal. A Sonatype 5xx, a DNS
  # failure and a reset connection are unknown errors. The analyzer thus stays on, it caches no
  # report, and it sends the full request again for the next dependency. A delay of one second
  # makes this a trickle, not a flood. On a good run, the delay occurs one time for each project,
  # and costs about 41 seconds. dependency-check reads each of its settings from a system property.
  # This is how the option reaches a setting that the Gradle DSL does not make available.
  #
  # The command below runs under `set -e`, but not under `pipefail`. The status of the pipeline is
  # thus the status of tee, which is always 0. That status would make a true CVE finding look like
  # a good run. Read the gradle status from PIPESTATUS instead. Read it in the next command, before
  # another command replaces it.
  set +e
  ./gradlew --no-daemon --stacktrace \
    -Danalyzer.ossindex.request.delay=1 \
    dependencyCheckAnalyze 2>&1 | tee "$CVE_LOG"
  CVE_RC=${PIPESTATUS[0]}
  set -e

  # Find which sources answered. This is only a label. The script writes it to a file, and prints
  # it. It does not change the exit code below. The search strings are message templates from
  # dependency-check. OssIndexKnownError writes "Sonatype OSS Index / Guide %s%s. %s".
  # OssIndexAnalyzer.prepareAnalyzer writes "... disabled due to missing credentials". If a new ODC
  # version changes these templates, the label becomes "unknown", and nothing else changes.
  if [[ "${ROR_CVE_OSS_INDEX:-}" == "false" ]]; then
    CVE_MODE="nvd-only-disabled"
  elif [[ ! -s "$CVE_LOG" ]]; then
    CVE_MODE="unknown"
  elif grep -qF 'disabled due to missing credentials' "$CVE_LOG"; then
    CVE_MODE="nvd-only-no-credentials"
  elif grep -qF 'Sonatype OSS Index / Guide' "$CVE_LOG"; then
    CVE_MODE="nvd-only"
  else
    CVE_MODE="full"
  fi
  echo "$CVE_MODE" > "$CVE_MODE_FILE"

  case "$CVE_MODE" in
    full)                    echo ">>> CVE scan sources: NVD + OSS Index" ;;
    nvd-only)                echo ">>> CVE scan sources: NVD only — OSS Index was unavailable:"
                             grep -F 'Sonatype OSS Index / Guide' "$CVE_LOG" | sort -u | head -3 ;;
    nvd-only-no-credentials) echo ">>> CVE scan sources: NVD only — no OSS Index credentials (expected on fork PRs)" ;;
    nvd-only-disabled)       echo ">>> CVE scan sources: NVD only — OSS Index disabled via ROR_CVE_OSS_INDEX=false" ;;
    unknown)                 echo ">>> CVE scan sources: unknown — no scan log was produced" ;;
  esac

  exit "$CVE_RC"
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
  ./gradlew --no-daemon --stacktrace core:test audit:test build-base:test
fi

run_integration_tests() {
  if [ "$#" -ne 1 ]; then
    echo "What ES module should I run integration tests for?"
    return 1
  fi

  ES_MODULE=$1
  # IT_PARALLELISM (the user-facing knob) = the gradle -PshardCount it feeds: K parallel shards.
  local parallelism="${IT_PARALLELISM:-1}"
  # Overridable so a bigger runner can raise it; see run_one for the memory budget.
  local IT_ORCHESTRATOR_JVMARGS="${IT_ORCHESTRATOR_JVMARGS:--Xmx2048m -XX:MaxMetaspaceSize=512m}"
  local esArgs=("-PesModule=$ES_MODULE")
  [ -n "$ES_VERSION" ] && esArgs+=("-PesVersion=$ES_VERSION")

  echo ">>> $ES_MODULE => ror-tools:test (serial gate) + integration-tests:shardedTest (${parallelism} shard(s)).."

  # Each gradle invocation runs in its OWN process group (setsid) so the trap can reap the whole tree;
  # appends the leader PID to GRADLE_PIDS (never pruned) and sets LAST_PID for the caller.
  LAST_PID=""
  run_one() {  # args: <gradle args...>
    # Cap the ORCHESTRATOR heap. These invocations only spawn the shard processes and build the ES
    # image; without this they inherit gradle.properties' -Xmx6144m, which the compile jobs need and
    # this one does not. The leg already holds, on a 16GB runner:
    #   K shard JVMs        K x (1024m heap + 512m metaspace)   (capped in ShardedGradlewTest)
    #   K test workers      K x 512m heap                       (itTestHeap)
    #   >= K ES containers  512m heap each, ~1.1GB RSS each
    # At K=4 that is already ~15GB, so a 6GB orchestrator ceiling on top is what tips the host into
    # OOM. A crashed daemon in integration_es80x reported daemonOpts=-Xmx6144m (RORDEV-2156).
    setsid ./gradlew --no-daemon -Dorg.gradle.jvmargs="$IT_ORCHESTRATOR_JVMARGS" "$@" &
    LAST_PID=$!; GRADLE_PIDS+=("$LAST_PID")
  }

  # 1) ror-tools:test ONCE, serially (cheap, no ES; gates the CI job). Also warms :build-base/:buildSrc.
  run_one ror-tools:test
  wait "$LAST_PID"; local rc=$?
  if [ "$rc" -ne 0 ]; then find . | grep hs_err | xargs cat 2>/dev/null || true; return "$rc"; fi

  # 2) All sharding orchestration lives in integration-tests:shardedTest (see its build.gradle):
  #    prebuild barrier via task deps, K child ./gradlew spawn/wait, ProcessHandle kill on cancel.
  run_one integration-tests:shardedTest "${esArgs[@]}" -PshardCount="$parallelism"
  wait "$LAST_PID"; rc=$?
  if [ "$rc" -ne 0 ]; then find . | grep hs_err | xargs cat 2>/dev/null || true; return "$rc"; fi
}

# One dispatch for every es*x module: the task name is integration_<module>, and the module is the
# only thing that varies. Adding an ES module therefore needs no edit here — only in the workflow
# matrix, which is where the list of modules to run actually lives.
if [[ $ROR_TASK =~ ^integration_(es[0-9]+x)$ ]]; then
  run_integration_tests "${BASH_REMATCH[1]}"
fi

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES generation (major: 6|7|8|9) should I verify plugins for?"
    return 1
  fi

  local es_major=$1

  # Capture first (process substitution would swallow a module-discovery failure into plain EOF).
  local modules
  modules=$(list_es_modules "$es_major") || { echo "ERROR: cannot list es${es_major}x modules"; return 1; }

  local module
  while IFS= read -r module; do
    [ -z "$module" ] && continue
    if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
      return 1
    fi
  done <<< "$modules"
}

# build_es<major>xx / upload_pre_es<major>xx / release_es<major>xx: three families over the same four
# ES generations, differing only in which function the generation is handed to.
if [[ $ROR_TASK =~ ^(build|upload_pre|release)_es([6-9])xx$ ]]; then
  ROR_TASK_FAMILY="${BASH_REMATCH[1]}"
  ES_MAJOR="${BASH_REMATCH[2]}"
  case "$ROR_TASK_FAMILY" in
    build)      build_ror_plugins "$ES_MAJOR" ;;
    upload_pre) publish_ror_plugins "$ES_MAJOR" "upload_pre" ;;
    release)    publish_ror_plugins "$ES_MAJOR" "release" ;;
  esac
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
  CURRENT_PLUGIN_VER=$(gradle_property pluginVersion) || exit 1
  PUBLISHED_PLUGIN_VER=$(gradle_property publishedPluginVersion) || exit 1

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
      publish_ror_es_prebuild_plugin "$VERSION" "$IMAGE_TAG"
      docker system prune -fa
    fi
  done

fi

# Runs once per pipeline: resolves each module's ELK version, publishes the test matrix, and
# dispatches one ROR KBN pre-build for all versions.
# Branches: ROR_KBN_TARGET_BRANCH and ROR_KBN_FALLBACK_BRANCH apply to the ROR KBN repo (not e2e).
# FALLBACK defaults to empty on purpose: outside CI there is no base branch, and the fallback chain
# in the e2e clone function already includes `develop` and `master`.
if [[ $ROR_TASK == "prepare_e2e_kbn_images" ]]; then
  prepare_e2e_kbn_images \
    "${E2E_ES_MODULES:?E2E_ES_MODULES is not set}" \
    "${ROR_KBN_TARGET_BRANCH:?ROR_KBN_TARGET_BRANCH is not set}" \
    "${ROR_KBN_FALLBACK_BRANCH:-}" \
    "${E2E_BUILD_ID:?E2E_BUILD_ID is not set}"
fi

# Runs once per ELK version, after prepare_e2e_kbn_images. Branches (E2E_TARGET_BRANCH and
# E2E_FALLBACK_BRANCH) apply to the e2e repo. E2E_ELK_VERSION comes from the published matrix; if
# missing, it is resolved from E2E_ES_MODULE as a fallback.
if [[ $ROR_TASK == "run_e2e_tests" ]]; then
  if [ -z "${E2E_ELK_VERSION:-}" ]; then
    E2E_ELK_VERSION=$(e2e_elk_version_for_module "${E2E_ES_MODULE:?neither E2E_ELK_VERSION nor E2E_ES_MODULE is set}")
  fi
  run_e2e_tests \
    "$E2E_ELK_VERSION" \
    "${E2E_TARGET_BRANCH:?E2E_TARGET_BRANCH is not set}" \
    "${E2E_FALLBACK_BRANCH:-}" \
    "${E2E_BUILD_ID:?E2E_BUILD_ID is not set — in CI it comes from the build_id output of e2e_prepare}"
fi
