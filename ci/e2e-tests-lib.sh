# Sourced, never executed.

# The e2e helpers. Together they run the Cypress e2e suite (docker env) against dev Docker images
# of both ROR plugins. Three entry points, one responsibility each:
#
#   order_e2e_kbn_images — order the ROR KBN images from the other repo, and wait for them
#   build_e2e_es_image   — build the ROR ES image
#   run_e2e_tests        — run the suite
#
# e2e_elk_version_for_module answers one more question, for a caller that shapes a matrix: the ELK
# version of one ES module.
#
# Both images carry a per-run tag (run-<build id>). Every entry point takes that build id as an
# argument, and none of them makes one up: the two repos must name the same images. Who creates the
# build id, and why a re-run must not change it: ci/CI.md#e2e-tests.
#
# The ROR KBN dispatch and wait helpers are not here. They live in the e2e repo, and a clone of it
# loads them: https://github.com/beshu-tech/readonlyrest-e2e-tests/blob/develop/ci/prebuild-images-lib.sh
# That file owns the cross-repo contract: image names, tag shape, workflow inputs, and the rule that
# the dispatch and the wait share a shell. Change how images are named or dispatched there, not here.
# This file holds only what belongs to this repo: the ROR ES image and the test suite.
# Note: docker_image_exists is defined in the shared file and replaces the copy in ci-lib.sh.

# shellcheck source=ci/log.sh
source "$(dirname "${BASH_SOURCE[0]}")/log.sh"

E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
# Path to the shared helpers in the e2e tests clone.
E2E_PREBUILD_IMAGES_LIB="ci/prebuild-images-lib.sh"

# Per-run image tag. Every entry point reads it from here, so they all name the same images.
e2e_run_tag() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    ci_log "Usage: e2e_run_tag <build id>"
    return 1
  fi
  echo "run-$1"
}

# Clones the e2e tests repo into a temp dir and prints the path. Try the target branch first, then
# the fallback, then `develop`, then `master`. The fallback branch matters: a change based on
# `develop` must use the `develop` suite, not `master`.
clone_e2e_tests_repo() {
  if [ "$#" -ne 2 ]; then
    ci_log "Usage: clone_e2e_tests_repo <target branch> <fallback branch>"
    return 1
  fi

  local TARGET_BRANCH=$1
  local FALLBACK_BRANCH=$2
  local E2E_DIR
  E2E_DIR=$(mktemp -d) || return 2

  local GIT_CONFIG_ARGS=()
  if [ -n "${ROR_GH_TOKEN:-}" ]; then
    local GIT_BASIC_AUTH XTRACE_WAS_ENABLED=false
    [[ $- == *x* ]] && XTRACE_WAS_ENABLED=true && set +x
    GIT_BASIC_AUTH=$(printf '%s:%s' x-access-token "$ROR_GH_TOKEN" | base64 | tr -d '\n')
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$GIT_BASIC_AUTH" >&2
    [ "$XTRACE_WAS_ENABLED" = true ] && set -x
    GIT_CONFIG_ARGS=(-c "http.https://github.com/.extraheader=AUTHORIZATION: basic $GIT_BASIC_AUTH")
  fi

  local CANDIDATES=("$TARGET_BRANCH")
  local BRANCH
  for BRANCH in "$FALLBACK_BRANCH" develop master; do
    [ -n "$BRANCH" ] && ! printf '%s\n' "${CANDIDATES[@]}" | grep -qxF "$BRANCH" && CANDIDATES+=("$BRANCH")
  done

  for BRANCH in "${CANDIDATES[@]}"; do
    # Clean the temp dir before each attempt (git refuses to clone into a non-empty dir).
    rm -rf "${E2E_DIR:?}" && mkdir -p "$E2E_DIR" || return 2
    if git "${GIT_CONFIG_ARGS[@]}" clone --depth 1 --branch "$BRANCH" "$E2E_TESTS_REPO" "$E2E_DIR" >/dev/null; then
      ci_log "Cloned branch $BRANCH of the e2e tests repo into $E2E_DIR."
      echo "$E2E_DIR"
      return 0
    fi
    ci_log "Cannot clone branch '$BRANCH' of the e2e tests repo."
  done

  ci_log "Cannot clone any of these e2e repo branches: ${CANDIDATES[*]}."
  return 3
}

# Load the ROR KBN pre-build helpers (dispatch_kbn_prebuild_image and wait_for_kbn_prebuild_images)
# from the e2e tests repo clone.
load_kbn_prebuild_helpers() {
  if [ "$#" -ne 1 ]; then
    ci_log "Usage: load_kbn_prebuild_helpers <e2e tests dir>"
    return 1
  fi

  local LIB="$1/$E2E_PREBUILD_IMAGES_LIB"
  if [ ! -f "$LIB" ]; then
    ci_log "The e2e tests clone ($1) has no $E2E_PREBUILD_IMAGES_LIB."
    ci_log "$E2E_TESTS_REPO owns the ROR KBN dispatch and wait helpers. The e2e branch in the clone is older than they are, so merge or rebase it."
    return 2
  fi

  # shellcheck source=/dev/null
  . "$LIB" || return 3
}

# Get the ELK version for an e2e module: the newest ES version it supports.
e2e_elk_version_for_module() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    ci_log "Usage: e2e_elk_version_for_module <es module>"
    return 1
  fi

  local MODULE=$1 VERSION
  local VERSION_FILE="${MODULE}/build/es-modules/newest-version.txt"
  # rm first: a failed gradle run must yield an error, never a stale version from a previous run.
  rm -f "$VERSION_FILE"
  ./gradlew --quiet ":${MODULE}:printNewestEsVersionForModule" </dev/null >&2 || {
    ci_log "Cannot resolve the newest ES version of module '$MODULE'."
    return 2
  }
  if [ ! -s "$VERSION_FILE" ]; then
    ci_log "':${MODULE}:printNewestEsVersionForModule' wrote no version."
    return 3
  fi
  VERSION=$(cat "$VERSION_FILE")

  echo "$VERSION"
}

# Reports why the ROR KBN order failed. One line, to the log and to the job summary. The shared lib
# already printed its own diagnosis. This adds the one sentence, and the link, that a reader of the
# job list needs. They do not have to open the step log.
#
# The dispatch and the wait reuse the same small exit codes for different faults. Codes 3 and 4 mean
# one thing to the dispatch and another to the wait. So the phase is part of the lookup.
# Usage: _report_kbn_order_failure <dispatch|wait> <exit code> <run url>
_report_kbn_order_failure() {
  local PHASE=$1 CODE=$2 RUN_URL=${3:-} REASON

  if [ "$PHASE" = dispatch ]; then
    case "$CODE" in
      1) REASON="no run tag names its run, so the dispatch did not start" ;;
      3) REASON="the dispatch of the ROR KBN pre-build workflow failed (check that it exists on the default branch of the ROR KBN repo, and that its inputs match)" ;;
      4) REASON="the dispatch started a run, but this job cannot identify it — that build continues, so re-run this job" ;;
      *) REASON="the dispatch of the ROR KBN pre-build failed (exit $CODE)" ;;
    esac
  else
    case "$CODE" in
      3) REASON="no ROR KBN pre-build run exists to follow" ;;
      4) REASON="the ROR KBN pre-build run did not end before the wait timeout" ;;
      5) REASON="the ROR KBN pre-build run succeeded, but the registry has no image for at least one version" ;;
      6) REASON="the ROR KBN pre-build run failed, and the fault is in the ROR KBN repo, not here" ;;
      7) REASON="this job cannot query the registry (rate limit, login or network), so it cannot verify the images" ;;
      8) REASON="this job cannot read the ROR KBN pre-build run — check that ROR_GH_TOKEN still grants actions:read" ;;
      *) REASON="the wait for the ROR KBN pre-build failed (exit $CODE)" ;;
    esac
  fi

  local LINE="This job did not order the KBN dev images: $REASON."
  [ -n "$RUN_URL" ] && LINE="$LINE Run: $RUN_URL"

  ci_log "$LINE"
  ci_log "The e2e test jobs are skipped, because there is nothing to test against."
  ci_log "Re-run the failed jobs. That re-runs this job, and places a new order under the same run tag. The ES images already published stay valid."

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### ❌ $LINE"
      echo ""
      echo "The ROR KBN repo builds the ROR KBN dev images. This job orders them and waits for them."
      echo "A failure here is about that build, not about the e2e suite."
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

# Orders the ROR KBN dev images. Call it one time for a whole run.
#
# Dispatches ONE ROR KBN pre-build for all the versions, then waits for it in the SAME shell. The
# two steps must share a shell: the shared lib finds a dispatched run by its title, in the seconds
# around the dispatch. Only the shell that dispatched the run can name it.
#
# Args: <elk versions> <target branch> <fallback branch> <build id>
#   elk versions    — the ELK versions to order (space-separated X.Y.Z)
#   target branch   — branch to build the ROR KBN plugin from
#   fallback branch — branch to use when the target branch is missing in the e2e repo
#   build id        — names the images this order produces; see the header
order_e2e_kbn_images() {
  if [ "$#" -ne 4 ]; then
    ci_log "Usage: order_e2e_kbn_images <elk versions> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ELK_VERSIONS=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$4") || return $?

  if [ -z "${ELK_VERSIONS// /}" ]; then
    ci_log "No ELK version to order ROR KBN images for. The caller passes the list."
    return 2
  fi

  # How long to wait for the WHOLE order. One run builds the versions one after another. A wait
  # that outlasts the caller can report nothing: the caller is stopped at its own timeout, and the
  # diagnosis below never reaches the log. So the caller sets ROR_KBN_WAIT_TIMEOUT_SECONDS, and
  # keeps it well below the time it gives itself. The default below applies to a local run.
  local WAIT_TIMEOUT=${ROR_KBN_WAIT_TIMEOUT_SECONDS:-7200}
  export ROR_KBN_WAIT_TIMEOUT_SECONDS=$WAIT_TIMEOUT

  ci_log "Ordering the e2e ROR KBN dev images: ELK [$ELK_VERSIONS], run tag $RUN_TAG, wait up to ${WAIT_TIMEOUT}s."

  # Clone the e2e repo only to load the shared dispatch and wait helpers. The test jobs clone it
  # again, for the runner.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  # Delete the clone on every exit path. The path is expanded into the trap now, because E2E_DIR is
  # local and no longer exists when the trap runs.
  trap "rm -rf '$E2E_DIR'" EXIT
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # One dispatch for all versions. It is dispatched even if the image exists, so that the per-run tag
  # is always applied.
  local STATUS=0
  dispatch_kbn_prebuild_image "$ELK_VERSIONS" "$TARGET_BRANCH" "$RUN_TAG" || STATUS=$?
  if [ "$STATUS" -ne 0 ]; then
    _report_kbn_order_failure dispatch "$STATUS" "${ROR_KBN_PREBUILD_RUN_URL:-}"
    return "$STATUS"
  fi

  # The wait follows ROR_KBN_PREBUILD_RUN_ID. The dispatch set it in this shell. The wait ends when
  # the run ends. Then it checks the registry for every image.
  wait_for_kbn_prebuild_images "$ELK_VERSIONS" "$RUN_TAG" || STATUS=$?
  if [ "$STATUS" -ne 0 ]; then
    _report_kbn_order_failure wait "$STATUS" "${ROR_KBN_PREBUILD_RUN_URL:-}"
    return "$STATUS"
  fi
}

# Publishes this repo's ROR ES dev image under the per-run tag. Call it one time for each ELK
# version.
#
# It goes through publish_ror_es_prebuild_plugin, as a plain pre-build publish does, so the
# sha-frozen-image skip applies: if the image for this commit already exists, that helper only adds
# the tag in the registry.
# Args: <elk version> <build id>
build_e2e_es_image() {
  if [ "$#" -ne 2 ]; then
    ci_log "Usage: build_e2e_es_image <elk version> <build id>"
    return 1
  fi

  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$2") || return $?

  ci_log "Building the ROR ES dev image: ELK $1, run tag $RUN_TAG."
  publish_ror_es_prebuild_plugin "$1" "$RUN_TAG"
}

# Run the Cypress test suite from an already-cloned e2e tests repo, against this run's dev images
# (ROR ES and ROR KBN with the same run tag).
run_e2e_against_dev_images() {
  if [ "$#" -ne 3 ]; then
    ci_log "Usage: run_e2e_against_dev_images <e2e tests dir> <elk version> <run tag>"
    return 1
  fi

  local E2E_DIR=$1
  local ELK_VERSION=$2
  local RUN_TAG=$3

  if [ -z "${ROR_ACTIVATION_KEY:-}" ] || [[ "${ROR_ACTIVATION_KEY}" == '$('* ]]; then
    ci_log "ROR_ACTIVATION_KEY is empty, or the shell did not expand it. The e2e Cypress tests need it."
    ci_log "Define ROR_ACTIVATION_KEY as a secret variable in the CI."
    return 2
  fi

  # Apply resource limits only on hosts with less than 12 GB memory. The limits prevent Kibana
  # OOM-kills on small hosts, but are unnecessary on large runners.
  local apply_limits=false
  local mem_kb
  mem_kb=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || echo 0)
  if [ "${mem_kb:-0}" -gt 0 ] && [ "$mem_kb" -lt 12000000 ]; then
    apply_limits=true
  fi
  ci_log "Running the e2e tests: ELK $ELK_VERSION, image tag $RUN_TAG (MemTotal=${mem_kb}kB, APPLY_RESOURCE_LIMITS=$apply_limits)."
  (
    cd "$E2E_DIR" || exit 1
    APPLY_RESOURCE_LIMITS="$apply_limits" ./runner.sh \
      --run e2e \
      --env docker \
      --elk "$ELK_VERSION" \
      --ror-es "$RUN_TAG" \
      --ror-kbn "$RUN_TAG" \
      --mode dev
  )
}

# Clones the e2e repo and runs the suite. Call it one time for each ELK version.
#
# Both dev images must already be in the registry: order_e2e_kbn_images waited for the ROR KBN one,
# and build_e2e_es_image published the ROR ES one.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch for the e2e suite
#   fallback branch — branch to use when target branch is missing in the e2e repo
#   build id        — names the images to test; see the header
run_e2e_tests() {
  if [ "$#" -ne 4 ]; then
    ci_log "Usage: run_e2e_tests <elk version> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ELK_VERSION=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$4") || return $?

  # Validate version format early (before network calls).
  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    ci_log "'$ELK_VERSION' is not an ELK version. The format is X.Y.Z."
    return 2
  fi

  ci_log "Running the e2e tests: ELK $ELK_VERSION, run tag $RUN_TAG."

  # Clone the e2e repo for the test runner.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?

  # Publish the path of the clone, so the caller can collect the results from it. Under GitHub
  # Actions only; a local run reads the path from the log.
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "E2E_TESTS_DIR=$E2E_DIR" >> "$GITHUB_ENV"
  fi

  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
