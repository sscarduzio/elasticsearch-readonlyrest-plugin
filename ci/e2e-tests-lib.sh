# Sourced by run-pipeline.sh — do not execute directly.

# Helpers for the four e2e tasks in run-pipeline.sh: `publish_e2e_matrix`, `order_e2e_kbn_images`,
# `build_e2e_es_image` and `run_e2e_tests`. Together they run the Cypress e2e suite (docker env)
# against dev Docker images of both ROR plugins. Each job has one responsibility. One job orders the
# ROR KBN images from the other repo and waits for them. One job builds the ROR ES image. The test
# jobs run the suite. What each job is: ci/CI.md#e2e-tests.
#
# Both images carry a per-run tag (run-<build id>). The build id is published by the matrix job and
# handed to every other job as a job output. No job may re-derive it, because a partial re-run bumps
# the GitHub run attempt without re-running the matrix job, which would rename the images.
#
# The ROR KBN dispatch/wait helpers are not defined here — they live in the e2e repo and are loaded
# from a clone of it: https://github.com/beshu-tech/readonlyrest-e2e-tests/blob/develop/ci/prebuild-images-lib.sh
# That file owns the cross-repo contract (image names, tag shape, workflow inputs, and the rule that
# the dispatch and the wait share a shell), so a change to how the images are named or dispatched
# belongs there, not here. This file handles only what is specific to this repo: the ROR ES image and
# running the test suite.
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

# Entry point for the `publish_e2e_matrix` task (the `e2e_matrix` job). Runs once per pipeline run,
# and does nothing but resolve versions: it publishes the matrix the downstream jobs fan out over,
# the ELK version list the order job dispatches for, and the build id whose run tag names every image
# of this run. Keep it free of side effects: "re-run failed jobs" skips a green job, so the build id
# and the published images survive a partial re-run.
#
# It stays in the toolchains container, because resolving a module's newest ES version is a Gradle
# call. Its `elk_versions` output is what lets the order job run outside that container.
#
# Args: <es modules> <build id>
#   es modules — e2e ES modules, space- or comma-separated (e.g. "es94x es818x es717x")
#   build id   — E2E_BUILD_ID (published to every other job as the `build_id` output)
publish_e2e_matrix() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: publish_e2e_matrix <es modules> <build id>"
    return 1
  fi

  local MATRIX ELK_VERSIONS
  MATRIX=$(e2e_matrix_json "$1") || return $?
  ELK_VERSIONS=$(echo "$MATRIX" | jq -r '[.include[].elk] | join(" ")')

  echo ">>> e2e matrix: $MATRIX (build id: $2, run tag: $(e2e_run_tag "$2"))"

  # A no-op outside GitHub Actions, where the matrix is printed above and the build id is the
  # caller's own.
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "matrix=$MATRIX" >> "$GITHUB_OUTPUT"
    echo "build_id=$2" >> "$GITHUB_OUTPUT"
    echo "elk_versions=$ELK_VERSIONS" >> "$GITHUB_OUTPUT"
  fi
}

# Report why the ROR KBN order failed, in one line, to the log and to the job summary. The shared lib
# has already printed its own diagnosis; this adds the sentence a reader of the job list needs, and
# the link, without making them open the step log.
#
# The two halves reuse the same small exit codes for different things (3 and 4 mean one thing to the
# dispatch and another to the wait), so the phase is part of the lookup.
# Usage: _report_kbn_order_failure <dispatch|wait> <exit code> <run url>
_report_kbn_order_failure() {
  local PHASE=$1 CODE=$2 RUN_URL=${3:-} REASON

  if [ "$PHASE" = dispatch ]; then
    case "$CODE" in
      1) REASON="the ROR KBN pre-build was not dispatched: no run tag, so its run could not be named" ;;
      3) REASON="the ROR KBN pre-build workflow could not be dispatched (check that it exists on the default branch of the ROR KBN repo and that its inputs match)" ;;
      4) REASON="the ROR KBN pre-build was dispatched, but the run it created could not be identified — that build continues; re-run this job" ;;
      *) REASON="dispatching the ROR KBN pre-build failed (exit $CODE)" ;;
    esac
  else
    case "$CODE" in
      3) REASON="there was no ROR KBN pre-build run to follow" ;;
      4) REASON="the ROR KBN pre-build run did not finish within the wait timeout" ;;
      5) REASON="the ROR KBN pre-build run succeeded, but at least one image is missing from the registry" ;;
      6) REASON="the ROR KBN pre-build run FAILED — the problem is in the ROR KBN repo, not here" ;;
      7) REASON="the registry could not be queried (rate limit, login or network), so the images could not be verified" ;;
      8) REASON="the ROR KBN pre-build run could not be read — check that ROR_GH_TOKEN still grants actions:read" ;;
      *) REASON="waiting for the ROR KBN pre-build failed (exit $CODE)" ;;
    esac
  fi

  local LINE="KBN dev images NOT ordered: $REASON."
  [ -n "$RUN_URL" ] && LINE="$LINE Run: $RUN_URL"

  echo ""
  echo "ERROR: $LINE"
  echo "       The e2e test jobs are skipped, because there is nothing to test against."
  echo "       Re-running the failed jobs re-runs THIS job, which places a fresh order under the"
  echo "       same run tag; the ES images already published stay valid."

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### ❌ $LINE"
      echo ""
      echo "The ROR KBN dev images are built by the ROR KBN repo. This job orders them and waits for"
      echo "them, so a failure here is about that build — not about the e2e suite."
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

# Entry point for the `order_e2e_kbn_images` task (the `e2e_order_kbn_images` job). Runs once per
# pipeline run.
#
# Dispatches ONE ROR KBN pre-build for every version of the matrix, then waits for it in the SAME
# shell. The two halves must share a shell. The shared lib identifies a dispatched run by a title
# search over the seconds around the dispatch, so only the shell that dispatched it can name the run
# to wait for.
#
# Args: <elk versions> <target branch> <fallback branch> <build id>
#   elk versions    — the `elk_versions` output of the matrix job (space-separated X.Y.Z)
#   target branch   — branch to build the ROR KBN plugin from
#   fallback branch — branch to use when the target branch is missing in the e2e repo
#   build id        — E2E_BUILD_ID, as PUBLISHED by the matrix job; it names the images ordered here
order_e2e_kbn_images() {
  if [ "$#" -ne 4 ]; then
    echo "Usage: order_e2e_kbn_images <elk versions> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ELK_VERSIONS=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$4") || return $?

  if [ -z "${ELK_VERSIONS// /}" ]; then
    echo "ERROR: no ELK versions to order ROR KBN images for. The matrix job publishes them as its"
    echo "       'elk_versions' output."
    return 2
  fi

  # One run builds the versions one after another, so it finishes after N build times. Scale the
  # wait with the count, off the shared lib's single-version default...
  local VERSION_COUNT
  VERSION_COUNT=$(echo "$ELK_VERSIONS" | wc -w | tr -d '[:space:]')
  local WAIT_TIMEOUT=$(( VERSION_COUNT * ${ROR_KBN_WAIT_TIMEOUT_SECONDS:-1800} ))
  # ...but never past what this job is allowed to live for. A wait that outlasts the job can report
  # nothing: GitHub kills the runner at `timeout-minutes` and the job dies with an opaque "exceeded
  # the maximum execution time" instead of the diagnosis below. Keep the cap well under
  # `timeout-minutes` on e2e_order_kbn_images in ci.yml, and raise both together.
  local WAIT_TIMEOUT_CAP=${ROR_KBN_WAIT_TIMEOUT_CAP_SECONDS:-7200}
  if [ "$WAIT_TIMEOUT" -gt "$WAIT_TIMEOUT_CAP" ]; then
    echo ">>> Capping the ROR KBN image wait at ${WAIT_TIMEOUT_CAP}s (scaled value was ${WAIT_TIMEOUT}s)"
    WAIT_TIMEOUT=$WAIT_TIMEOUT_CAP
  fi
  export ROR_KBN_WAIT_TIMEOUT_SECONDS=$WAIT_TIMEOUT

  echo ">>> Ordering e2e ROR KBN dev images: ELK [$ELK_VERSIONS], run tag: $RUN_TAG, wait up to ${WAIT_TIMEOUT}s"

  # Clone the e2e repo only to load the shared dispatch and wait helpers (test jobs clone it
  # separately, for the runner).
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  # Drop the clone however this exits. The path is expanded into the trap now, because E2E_DIR is
  # local and gone by the time the trap runs.
  trap "rm -rf '$E2E_DIR'" EXIT
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # One dispatch for every version. It is dispatched even if the image exists, so the per-run tag
  # always gets applied.
  local STATUS=0
  dispatch_kbn_prebuild_image "$ELK_VERSIONS" "$TARGET_BRANCH" "$RUN_TAG" || STATUS=$?
  if [ "$STATUS" -ne 0 ]; then
    _report_kbn_order_failure dispatch "$STATUS" "${ROR_KBN_PREBUILD_RUN_URL:-}"
    return "$STATUS"
  fi

  # The wait follows ROR_KBN_PREBUILD_RUN_ID, which the dispatch set in this shell. It ends when the
  # run ends, then checks the registry for every image.
  wait_for_kbn_prebuild_images "$ELK_VERSIONS" "$RUN_TAG" || STATUS=$?
  if [ "$STATUS" -ne 0 ]; then
    _report_kbn_order_failure wait "$STATUS" "${ROR_KBN_PREBUILD_RUN_URL:-}"
    return "$STATUS"
  fi

  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "kbn_run_url=${ROR_KBN_PREBUILD_RUN_URL:-}" >> "$GITHUB_OUTPUT"
  fi
}

# Entry point for the `build_e2e_es_image` task (the `e2e_build_es_images` job). Runs once per ELK
# version, while the order job waits for the other repo's build.
#
# Publishes this repo's ROR ES dev image under the per-run alias tag, through the same helper the
# standalone pre-build task uses. The sha-frozen-image skip applies: if the image for this commit
# already exists, the helper only retags it in the registry.
# Args: <elk version> <build id>
build_e2e_es_image() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: build_e2e_es_image <elk version> <build id>"
    return 1
  fi

  local RUN_TAG
  RUN_TAG=$(e2e_run_tag "$2") || return $?

  echo ">>> Building the ROR ES dev image: ELK $1, run tag: $RUN_TAG"
  publish_ror_es_prebuild_plugin "$1" "$RUN_TAG"
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

# Entry point for the `run_e2e_tests` task. Runs once per ELK version. It clones the e2e repo and
# runs the suite. Both dev images are already in the registry: e2e_order_kbn_images verified the ROR
# KBN one, e2e_build_es_images published the ROR ES one.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch for the e2e suite
#   fallback branch — branch to use when target branch is missing in the e2e repo
#   build id        — E2E_BUILD_ID (from the matrix job; do not re-derive it)
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

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # Clone the e2e repo for the test runner.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?

  # Export the test directory path so later steps can collect results (no-op outside CI).
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "E2E_TESTS_DIR=$E2E_DIR" >> "$GITHUB_ENV"
  fi

  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
