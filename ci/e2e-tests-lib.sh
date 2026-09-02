# Sourced by run-pipeline.sh — do not execute directly.

# Helpers for the `prepare_e2e_kbn_images` and `run_e2e_tests` tasks in run-pipeline.sh. Together
# they run the Cypress e2e suite (docker env) against dev Docker images of both ROR plugins.
# What the two jobs are and why they are two: ci/README.md#e2e-tests.
#
# Both images carry a per-run tag (run-<build id>). The build id is created by prepare_e2e_kbn_images
# and passed to the test jobs as a job output. The test jobs must not re-derive it, because a partial
# re-run bumps the GitHub run attempt without re-running the prepare job, causing the two sides to
# name different images.
#
# The ROR KBN dispatch/wait helpers are not defined here — they live in the e2e repo and are loaded
# from a clone of it: https://github.com/beshu-tech/readonlyrest-e2e-tests/blob/develop/ci/prebuild-images-lib.sh
# That file owns the cross-repo contract (image names, tag shape, workflow inputs), so a change to
# how the images are named or dispatched belongs there, not here. This file handles only what is
# specific to this repo: the ROR ES image and running the test suite.
# Note: docker_image_exists is defined in the shared file and replaces the copy in ci-lib.sh.

E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
# Path to the shared helpers in the e2e tests clone.
E2E_PREBUILD_IMAGES_LIB="ci/prebuild-images-lib.sh"

# Per-run image tag. Both jobs use this, so they reference the same images.
e2e_run_tag() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: e2e_run_tag <build id>" >&2
    return 1
  fi
  echo "run-$1"
}

# Clones the e2e tests repo into a temp dir and prints the path. Try the target branch first, then
# the fallback, then `develop`, then `master`. The fallback branch matters: a change based on
# `develop` must use the `develop` suite, not `master`.
clone_e2e_tests_repo() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: clone_e2e_tests_repo <target branch> <fallback branch>" >&2
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

  echo "" >&2
  for BRANCH in "${CANDIDATES[@]}"; do
    # Clean the temp dir before each attempt (git refuses to clone into a non-empty dir).
    rm -rf "${E2E_DIR:?}" && mkdir -p "$E2E_DIR" || return 2
    if git "${GIT_CONFIG_ARGS[@]}" clone --depth 1 --branch "$BRANCH" "$E2E_TESTS_REPO" "$E2E_DIR" >/dev/null; then
      echo ">>> Cloned e2e tests repo (branch: $BRANCH) into $E2E_DIR" >&2
      echo "$E2E_DIR"
      return 0
    fi
    echo ">>> Could not clone e2e tests branch '$BRANCH'" >&2
  done

  echo "ERROR: none of the e2e repo branches [${CANDIDATES[*]}] could be cloned" >&2
  return 3
}

# Load the ROR KBN pre-build helpers (dispatch_kbn_prebuild_image and wait_for_kbn_prebuild_images)
# from the e2e tests repo clone.
load_kbn_prebuild_helpers() {
  if [ "$#" -ne 1 ]; then
    echo "Usage: load_kbn_prebuild_helpers <e2e tests dir>"
    return 1
  fi

  local LIB="$1/$E2E_PREBUILD_IMAGES_LIB"
  if [ ! -f "$LIB" ]; then
    echo "ERROR: $E2E_PREBUILD_IMAGES_LIB not found in the e2e tests clone ($1)"
    echo "       The ROR KBN pre-build dispatch and wait helpers are owned by $E2E_TESTS_REPO."
    echo "       The checked-out e2e branch predates them — merge/rebase it so the file is present."
    return 2
  fi

  # shellcheck source=/dev/null
  . "$LIB" || return 3
}

# Get the ELK version for an e2e module: the newest ES version it supports. Gradle may print extra
# output, so use only the last line and check that it looks like a version.
e2e_elk_version_for_module() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: e2e_elk_version_for_module <es module>" >&2
    return 1
  fi

  local MODULE=$1 OUTPUT VERSION
  OUTPUT=$(./gradlew --quiet ":${MODULE}:printNewestEsVersionForModule" </dev/null) || {
    echo "ERROR: could not resolve the newest ES version of module '$MODULE'" >&2
    return 2
  }
  VERSION=$(echo "$OUTPUT" | sed '/^[[:space:]]*$/d' | tail -n 1 | tr -d '[:space:]')

  if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "ERROR: ':${MODULE}:printNewestEsVersionForModule' did not print a version. Got:" >&2
    echo "$OUTPUT" >&2
    return 3
  fi

  echo "$VERSION"
}

# Build the test matrix as GitHub Actions JSON (one entry per ES module with its ELK version).
# Job names are based on the module, so branch-protection checks survive version bumps.
# Usage: e2e_matrix_json "es94x es818x es717x"
e2e_matrix_json() {
  if [ "$#" -ne 1 ] || [ -z "${1// /}" ]; then
    echo "Usage: e2e_matrix_json <es modules>" >&2
    return 1
  fi

  local MODULE VERSION ENTRIES=()
  for MODULE in $(echo "$1" | tr ',' ' '); do
    VERSION=$(e2e_elk_version_for_module "$MODULE") || return $?
    echo ">>> $MODULE -> ELK $VERSION" >&2
    ENTRIES+=("$(jq -cn --arg m "$MODULE" --arg v "$VERSION" '{module: $m, elk: $v}')")
  done

  printf '%s\n' "${ENTRIES[@]}" | jq -cs '{include: .}'
}

# Pass the dispatched ROR KBN run ID, URL, and wait timeout to the test jobs. They run as separate
# processes on separate machines, and the run ID is what they wait on.
#
# Outside GitHub Actions there is no job output, so the same three values are printed as export
# lines. A local run of the `run_e2e_tests` task is a separate shell, and without them it stops at
# once, because the wait has no run to follow.
# Usage: export_kbn_prebuild_run_info <version count>
export_kbn_prebuild_run_info() {
  # The test jobs wait for the run, and one run builds all the versions one after another, so it
  # finishes after N build times. Scale the timeout by the number of versions.
  local WAIT_TIMEOUT=$(( ${1:-1} * ${ROR_KBN_WAIT_TIMEOUT_SECONDS:-1800} ))

  if [ -z "${GITHUB_OUTPUT:-}" ]; then
    echo ""
    echo ">>> Not running in GitHub Actions, so there is no job output to write. A test run is a"
    echo "    separate shell, and its wait needs the run below. Export these first:"
    echo ""
    echo "      export ROR_KBN_PREBUILD_RUN_ID='${ROR_KBN_PREBUILD_RUN_ID:-}'"
    echo "      export ROR_KBN_PREBUILD_RUN_URL='${ROR_KBN_PREBUILD_RUN_URL:-}'"
    echo "      export ROR_KBN_WAIT_TIMEOUT_SECONDS=$WAIT_TIMEOUT"
    echo ""
    return 0
  fi

  {
    echo "kbn_run_id=${ROR_KBN_PREBUILD_RUN_ID:-}"
    echo "kbn_run_url=${ROR_KBN_PREBUILD_RUN_URL:-}"
    echo "kbn_wait_timeout_seconds=$WAIT_TIMEOUT"
  } >> "$GITHUB_OUTPUT"
}

# Entry point for the `prepare_e2e_kbn_images` task. Runs once per pipeline run.
# Args: <es modules> <target branch> <fallback branch> <build id>
#   es modules      — e2e ES modules, space- or comma-separated (e.g. "es94x es818x es717x")
#   target branch   — branch to build the ROR KBN plugin from
#   fallback branch — branch to use when target branch is missing in the e2e repo
#   build id        — E2E_BUILD_ID (published to test jobs as `build_id` output)
prepare_e2e_kbn_images() {
  if [ "$#" -ne 4 ]; then
    echo "Usage: prepare_e2e_kbn_images <es modules> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ES_MODULES=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$4") || return $?

  local MATRIX ELK_VERSIONS
  MATRIX=$(e2e_matrix_json "$ES_MODULES") || return $?
  ELK_VERSIONS=$(echo "$MATRIX" | jq -r '[.include[].elk] | join(" ")')
  # Export the matrix and build id to GitHub (no-op outside CI).
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "matrix=$MATRIX" >> "$GITHUB_OUTPUT"
    echo "build_id=$4" >> "$GITHUB_OUTPUT"
  fi

  echo ">>> Preparing e2e ROR KBN dev images: ELK [$ELK_VERSIONS], run tag: $RUN_TAG"

  # Clone the e2e repo only to load the shared dispatch helper (test jobs clone it separately).
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # Dispatch the ROR KBN build (non-blocking). It is dispatched even if the image exists, so the
  # per-run tag always gets applied.
  dispatch_kbn_prebuild_image "$ELK_VERSIONS" "$TARGET_BRANCH" "$RUN_TAG" || return $?

  export_kbn_prebuild_run_info "$(echo "$MATRIX" | jq '.include | length')"
}

# Run the Cypress test suite from an already-cloned e2e tests repo, against this run's dev images
# (ROR ES and ROR KBN with the same run tag).
run_e2e_against_dev_images() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: run_e2e_against_dev_images <e2e tests dir> <elk version> <run tag>"
    return 1
  fi

  local E2E_DIR=$1
  local ELK_VERSION=$2
  local RUN_TAG=$3

  if [ -z "${ROR_ACTIVATION_KEY:-}" ] || [[ "${ROR_ACTIVATION_KEY}" == '$('* ]]; then
    echo "ERROR: ROR_ACTIVATION_KEY is not set or was not resolved by the pipeline (required to run the e2e Cypress tests)"
    echo "       Make sure ROR_ACTIVATION_KEY is defined as a secret variable in the CI."
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
  echo ""
  echo ">>> Running e2e tests: ELK $ELK_VERSION, image tag: $RUN_TAG (MemTotal=${mem_kb}kB, APPLY_RESOURCE_LIMITS=$apply_limits)"
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

# Entry point for the `run_e2e_tests` task. Runs once per ELK version, after prepare_e2e_kbn_images.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch for the e2e suite
#   fallback branch — branch to use when target branch is missing in the e2e repo
#   build id        — E2E_BUILD_ID (from the preparing job; do not re-derive it)
run_e2e_tests() {
  if [ "$#" -ne 4 ]; then
    echo "Usage: run_e2e_tests <elk version> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ELK_VERSION=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$4") || return $?

  # Validate version format early (before network calls).
  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ELK version format. Expected format: X.Y.Z"
    return 2
  fi

  # The wait below follows the ROR KBN pre-build run, so the run id is necessary. It comes from the
  # job environment and nothing here sets it, so check it before the clone and the Gradle build: a
  # job that cannot wait must not spend minutes to say so. The e2e_prepare job dispatches the run on
  # another machine and publishes the id as its `kbn_run_id` output. That job also fails if it
  # cannot identify the run it started, so the id is set whenever it succeeded.
  if [ -z "${ROR_KBN_PREBUILD_RUN_ID:-}" ]; then
    echo "ERROR: ROR_KBN_PREBUILD_RUN_ID is empty, so there is no ROR KBN run to wait for."
    echo "       The e2e_prepare job publishes the id as its 'kbn_run_id' output, and ci.yml passes"
    echo "       that output to this job. Check both."
    return 3
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # Clone the e2e repo (needed for both the wait helper and the test runner).
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # Export the test directory path so later steps can collect results (no-op outside CI).
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "E2E_TESTS_DIR=$E2E_DIR" >> "$GITHUB_ENV"
  fi

  # Build and publish the ROR ES dev image (Gradle is skipped if the image already exists).
  publish_ror_es_prebuild_plugin "$ELK_VERSION" "$RUN_TAG" || return $?

  # Wait for the ROR KBN image. The run id was checked at the top of this function, because the wait
  # follows that run and the id arrives with the job environment.
  wait_for_kbn_prebuild_images "$ELK_VERSION" "$RUN_TAG" || return $?

  # Both images are now available; run the test suite.
  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
