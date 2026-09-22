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
  ci_log "Termination signal received. Killing the gradle trees, and reaping the containers of this CI job."
  # Negative PID = signal the whole process group (gradle + its worker JVMs).
  for pid in "${GRADLE_PIDS[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  sleep 5
  for pid in "${GRADLE_PIDS[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
  reap_ci_job_containers
  exit 1
}
trap terminate SIGTERM SIGINT

ci_log "Running the CI task $ROR_TASK ($0)."

# Log file friendly Gradle output
export TERM=dumb

task_license_check() {
  ci_log "Checking that every source file has a license header."
  ./gradlew --no-daemon license
}

task_format_code_check() {
  ci_log "Running the format check."
  ./gradlew --no-daemon formatCodeCheck
}

task_cve_check() {
  ci_log "Running the CVE checks."
  # Convert DEPENDENCY_CHECK_DATA_DIR to an absolute path before invoking Gradle.
  # H2 SHUTDOWN DEFRAG closes the DB during defrag; when dependency-check reopens
  # it in read-only mode for analysis, a relative path fails the isFile() check
  # because the CWD context is lost after H2 closes the connection.
  if [[ -n "$DEPENDENCY_CHECK_DATA_DIR" ]]; then
    export DEPENDENCY_CHECK_DATA_DIR="$(cd "$DEPENDENCY_CHECK_DATA_DIR" 2>/dev/null && pwd || echo "$(pwd)/$DEPENDENCY_CHECK_DATA_DIR")"
    ci_log "DEPENDENCY_CHECK_DATA_DIR resolves to $DEPENDENCY_CHECK_DATA_DIR."
  fi
  CVE_LOG="$(pwd)/build/cve-scan.log"
  CVE_MODE_FILE="$(pwd)/build/cve-scan-mode.txt"
  mkdir -p "$(dirname "$CVE_LOG")"

  # Do not add --parallel here. dependencyCheckAnalyze runs in all 41 subprojects that apply
  # readonlyrest.base-common-conventions, and those subprojects share one H2 data directory.
  # Only a sequential run is safe.
  #
  # --continue is what makes the result complete. failBuildOnCVSS is 3, thus one finding fails the
  # task of one subproject. Without --continue, gradle stops there, and the later subprojects are
  # never scanned. The reports on disk would then cover a part of the repository, and no one could
  # see which part. --continue keeps each subproject independent: every one is scanned, every
  # report is written, and the build still fails. It does not make the run parallel, thus the H2
  # directory stays safe.
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
  ./gradlew --no-daemon --stacktrace --continue \
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
  elif ! grep -qE '^(BUILD SUCCESSFUL|BUILD FAILED)' "$CVE_LOG"; then
    # Gradle writes one of these two lines when it ends, and it writes it after --continue has run
    # the last task. No such line means that nothing ended the run in an orderly way: the timeout
    # of the caller stopped it, or the runner killed the JVM. The reports on disk then cover only the
    # subprojects that came first. Such a run must not name its sources, because it does not know
    # what it did not read.
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
    full)                    ci_log "CVE scan sources: NVD and OSS Index." ;;
    nvd-only)                ci_log "CVE scan sources: NVD only. OSS Index did not answer:"
                             grep -F 'Sonatype OSS Index / Guide' "$CVE_LOG" | sort -u | head -3 >&2 ;;
    nvd-only-no-credentials) ci_log "CVE scan sources: NVD only. There are no OSS Index credentials, which a fork PR expects." ;;
    nvd-only-disabled)       ci_log "CVE scan sources: NVD only. ROR_CVE_OSS_INDEX=false disables OSS Index." ;;
    unknown)                 ci_log "CVE scan sources: unknown. The scan did not run to the end." ;;
  esac

  exit "$CVE_RC"
}

task_compile_codebase_check() {
  ci_log "Compiling the codebase."
  ./gradlew --no-daemon classes
}

task_audit_build_check() {
  ci_log "Cross building the audit module."
  ./gradlew --no-daemon --stacktrace audit:crossBuildAssemble
}

run_core_tests() {
  local args=()
  mapfile -t args < <(windows_gradle_args)

  local suites=(core:test ror-tools:test)
  is_windows || suites+=(audit:test build-base:test)

  ci_log "Running the unit tests (${suites[*]})."
  ./gradlew --no-daemon --stacktrace "${args[@]}" "${suites[@]}" \
    || { dump_hs_err_files; return 1; }
}

task_core_tests() {
  run_core_tests
}

run_integration_tests() {
  if [ "$#" -ne 1 ]; then
    ci_log "Usage: run_integration_tests <es module>"
    return 1
  fi

  ES_MODULE=$1
  # IT_PARALLELISM (the user-facing knob) = the gradle -PshardCount it feeds: K parallel shards.
  local parallelism="${IT_PARALLELISM:-1}"
  # Overridable so a bigger runner can raise it; see run_one for the memory budget.
  local IT_ORCHESTRATOR_JVMARGS="${IT_ORCHESTRATOR_JVMARGS:--Xmx2048m -XX:MaxMetaspaceSize=512m}"
  local esArgs=("-PesModule=$ES_MODULE")
  [ -n "$ES_VERSION" ] && esArgs+=("-PesVersion=$ES_VERSION")

  # What a Windows runner adds to every gradle call here (nothing on Linux).
  local platformArgs=()
  mapfile -t platformArgs < <(windows_gradle_args)

  ci_log "$ES_MODULE runs integration-tests:shardedTest on ${parallelism} shard(s)."

  # Each gradle invocation runs in its OWN process group (setsid) so the trap can reap the whole tree;
  # appends the leader PID to GRADLE_PIDS (never pruned) and sets LAST_PID for the caller.
  LAST_PID=""
  run_one() {  # args: <gradle args...>
    # Cap the ORCHESTRATOR heap. These invocations only spawn the shard processes and build the ES
    # image; without this they inherit gradle.properties' -Xmx6144m, which a compile task needs and
    # this one does not. This task already holds, on a 16GB runner:
    #   K shard JVMs        K x (1024m heap + 512m metaspace)   (capped in ShardedGradlewTest)
    #   K test workers      K x 512m heap                       (itTestHeap)
    #   >= K ES nodes       512m heap each, ~1.1GB RSS each     (containers; native on Windows)
    # At K=4 that is already ~15GB, so a 6GB orchestrator ceiling on top is what tips the host into
    # OOM. A crashed daemon in integration_es80x reported daemonOpts=-Xmx6144m (RORDEV-2156).
    #
    # Git Bash has no setsid, so Windows has no process group to signal. The SIGTERM trap still
    # kills the leader, and the runner ends the ephemeral VM anyway.
    local launcher=()
    command -v setsid >/dev/null 2>&1 && launcher=(setsid)
    "${launcher[@]}" ./gradlew --no-daemon -Dorg.gradle.jvmargs="$IT_ORCHESTRATOR_JVMARGS" "${platformArgs[@]}" "$@" &
    LAST_PID=$!; GRADLE_PIDS+=("$LAST_PID")
  }

  # All sharding orchestration lives in integration-tests:shardedTest (see its build.gradle):
  # prebuild barrier via task deps, K child ./gradlew spawn/wait, ProcessHandle kill on cancel.
  local rc=0
  run_one integration-tests:shardedTest "${esArgs[@]}" -PshardCount="$parallelism"
  wait "$LAST_PID"; rc=$?
  if [ "$rc" -ne 0 ]; then dump_hs_err_files; return "$rc"; fi
}

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    ci_log "Usage: build_ror_plugins <es major>"
    return 1
  fi

  local es_major=$1

  # Capture first (process substitution would swallow a module-discovery failure into plain EOF).
  local modules
  modules=$(list_es_modules "$es_major") || { ci_log "Cannot list the es${es_major}x modules."; return 1; }

  if [ -z "$modules" ]; then
    ci_log "No es${es_major}x module exists, so there is nothing to build."
    return 1
  fi

  local module
  while IFS= read -r module; do
    [ -z "$module" ] && continue
    if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
      return 1
    fi
  done <<< "$modules"
}

# Three tasks over the ES generations, each with its own function. The caller passes the generation
# in ES_MAJOR, so any major works and ES 10 needs no edit here.
task_build_plugins()      { build_ror_plugins   "${ES_MAJOR:?ES_MAJOR is not set}"; }
task_upload_pre_plugins() { publish_es_major "${ES_MAJOR:?ES_MAJOR is not set}" "upload_pre"; }
task_release_plugins()    { publish_es_major "${ES_MAJOR:?ES_MAJOR is not set}" "release"; }

check_maven_artifacts_exist() {
  local CURRENT_VERSION="$1"

  local ARTIFACT_URL="https://repo1.maven.org/maven2/tech/beshu/ror/audit_3/$CURRENT_VERSION/"
  ci_log "Checking for Maven artifacts at $ARTIFACT_URL."
  
  local MVN_STATUS=$(curl -L --write-out '%{http_code}' --silent --output /dev/null "$ARTIFACT_URL" || echo "000")
  
  if [[ $MVN_STATUS == "404" ]]; then
    ci_log "Maven Central has no artifact for this version."
    return 1
  elif [[ $MVN_STATUS == "200" ]]; then
    ci_log "Maven Central already holds the artifacts of version $CURRENT_VERSION."
    return 0
  else
    ci_log "Maven Central answered HTTP $MVN_STATUS, so this run cannot tell whether the artifacts exist."
    ci_log "The run stops here, rather than publish over an artifact that exists."
    exit 1
  fi
}

task_publish_maven_artifacts() {
  CURRENT_PLUGIN_VER=$(gradle_property pluginVersion) || exit 1
  PUBLISHED_PLUGIN_VER=$(gradle_property publishedPluginVersion) || exit 1

  if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
    if check_maven_artifacts_exist "$CURRENT_PLUGIN_VER"; then
      ci_log "The artifacts exist, so this run publishes no audit module artifact."
    else
      ci_log "Publishing the audit module artifacts to the sonatype repo."
      ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
    fi
  else
    ci_log "pluginVersion is $CURRENT_PLUGIN_VER and publishedPluginVersion is $PUBLISHED_PLUGIN_VER."
    ci_log "The two versions differ, so this run publishes no audit module artifact."
  fi
}

task_publish_pre_builds_docker_images() {

  if [ -z "$(echo "$BUILD_ROR_ES_VERSIONS" | tr -d '[:space:],')" ]; then
    ci_log "BUILD_ROR_ES_VERSIONS is not set. The caller passes the ES versions to build."
    exit 1
  fi

  # IMAGE_TAG is optional, and a caller may pass a whitespace-only value (the default of a
  # workflow_dispatch input is one space), so normalize such a value to empty.
  IMAGE_TAG="$(echo "${IMAGE_TAG:-}" | tr -d '[:space:]')"

  IFS=', ' read -r -a VERSIONS <<< "$BUILD_ROR_ES_VERSIONS"
  for VERSION in "${VERSIONS[@]}"; do
    if [ -n "$VERSION" ]; then
      publish_ror_es_prebuild_plugin "$VERSION" "$IMAGE_TAG"
      # Each ES version pulls its own ~1.5 GB base image, so reclaim between versions. On a shared
      # self-hosted daemon `-a` would also delete the images other repos' runners are using — see
      # cleanup_docker_and_build in ci/publish-ror-plugins.sh.
      if is_shared_docker_host; then
        docker image prune -f || true
        docker builder prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" || true
      else
        docker system prune -fa
      fi
    fi
  done

}

# Call this task one time for a whole run. It dispatches ONE ROR KBN pre-build for all the
# versions, then waits for it in the same shell. A failed ROR KBN build fails this task, and
# another call of it places a new order.
# Branches: ROR_KBN_TARGET_BRANCH and ROR_KBN_FALLBACK_BRANCH apply to the ROR KBN repo (not e2e).
# FALLBACK defaults to empty on purpose: outside CI there is no base branch, and the fallback chain
# in the e2e clone function already includes `develop` and `master`.
task_order_e2e_kbn_images() {
  order_e2e_kbn_images \
    "${E2E_ELK_VERSIONS:?E2E_ELK_VERSIONS is not set — the caller passes the ELK versions to order}" \
    "${ROR_KBN_TARGET_BRANCH:?ROR_KBN_TARGET_BRANCH is not set}" \
    "${ROR_KBN_FALLBACK_BRANCH:-}" \
    "${E2E_BUILD_ID:?E2E_BUILD_ID is not set — one build id names the dev images of a whole run, and the caller passes it (ci/CI.md#e2e-tests)}"
}

# Call this task one time for each ES module. It publishes this repo's ROR ES dev image under the
# per-run tag. The caller names the module in E2E_ES_MODULE, and this task derives the ELK version
# from it. A caller that already knows the version passes E2E_ELK_VERSION instead.
task_build_e2e_es_image() {
  if [ -z "${E2E_ELK_VERSION:-}" ]; then
    E2E_ELK_VERSION=$(e2e_elk_version_for_module "${E2E_ES_MODULE:?neither E2E_ELK_VERSION nor E2E_ES_MODULE is set}")
  fi
  build_e2e_es_image \
    "$E2E_ELK_VERSION" \
    "${E2E_BUILD_ID:?E2E_BUILD_ID is not set — one build id names the dev images of a whole run, and the caller passes it (ci/CI.md#e2e-tests)}"
}

# Call this task one time for each ES module, after both images are published. The branches
# (E2E_TARGET_BRANCH and E2E_FALLBACK_BRANCH) apply to the e2e repo. The caller names the module in
# E2E_ES_MODULE, and this task derives the ELK version from it. A caller that already knows the
# version passes E2E_ELK_VERSION instead.
task_run_e2e_tests() {
  if [ -z "${E2E_ELK_VERSION:-}" ]; then
    E2E_ELK_VERSION=$(e2e_elk_version_for_module "${E2E_ES_MODULE:?neither E2E_ELK_VERSION nor E2E_ES_MODULE is set}")
    # Publish the version, because a caller that collects the reports names their folder after it.
    # `if`, not `&&`: outside GitHub Actions a false `&&` would end a `set -e` script with status 1.
    if [ -n "${GITHUB_ENV:-}" ]; then
      echo "E2E_ELK_VERSION=$E2E_ELK_VERSION" >> "$GITHUB_ENV"
    fi
  fi
  run_e2e_tests \
    "$E2E_ELK_VERSION" \
    "${E2E_TARGET_BRANCH:?E2E_TARGET_BRANCH is not set}" \
    "${E2E_FALLBACK_BRANCH:-}" \
    "${E2E_BUILD_ID:?E2E_BUILD_ID is not set — one build id names the dev images of a whole run, and the caller passes it (ci/CI.md#e2e-tests)}"
}

# One dispatch point. A task is a `task_<name>` function, so a new task needs no edit here.
# `integration_<module>` is the exception: the ES module is part of the name, and a new ES module
# must not need an edit either.
if declare -F "task_$ROR_TASK" >/dev/null; then
  "task_$ROR_TASK"
elif [[ $ROR_TASK =~ ^integration_(es[0-9]+x)$ ]]; then
  run_integration_tests "${BASH_REMATCH[1]}"
else
  ci_log "Unknown ROR_TASK '$ROR_TASK'."
  exit 1
fi
