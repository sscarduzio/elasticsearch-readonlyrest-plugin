# Sourced by run-pipeline.sh — do not execute directly.

# Helpers for the `prepare_e2e_kbn_images` and `run_e2e_tests` tasks in run-pipeline.sh.
#
# Together they run the Cypress e2e suite (docker env) against dev Docker images of both ROR plugins,
# built for this pipeline run. The work is split over two CI jobs:
#
#   prepare_e2e_kbn_images — once per run: resolves the ELK version of every e2e ES module, publishes
#     that list as the test matrix, and starts ONE ROR KBN image build covering all the versions. It
#     does not wait for it. One build per version would make it ambiguous which build to track.
#
#   run_e2e_tests — once per ELK version, in parallel: builds and publishes the ROR ES image for its
#     own version (the expensive step), waits for the ROR KBN image of that version, then runs the
#     suite against both.
#
# Both images carry a per-run tag (run-<build id>), so a run only ever tests its own images. The
# build id is minted by prepare_e2e_kbn_images and handed to the test jobs as a job output. They must
# not derive it themselves: a partial re-run bumps the GitHub run attempt without re-running the
# preparing job, so the two sides would name different images and the test jobs would wait forever.
#
# The ROR KBN dispatch/wait helpers are not defined here. The ROR KBN repo and the e2e repo need the
# same pair, so they live once in the e2e repo (ci/prebuild-images-lib.sh) and are loaded from a
# clone of it. Only what is specific to this repo stays here: the ROR ES image and running the suite.
#
# Note: that shared file also defines docker_image_exists, which then replaces ci-lib.sh's copy for
# the rest of the process. The two bodies are identical, so this is harmless.

E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
# Where the shared helpers live inside the e2e tests clone.
E2E_PREBUILD_IMAGES_LIB="ci/prebuild-images-lib.sh"

# The per-run image tag. Both jobs take it from here, so they cannot disagree about which images
# belong to this run.
e2e_run_tag() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: e2e_run_tag <build id>" >&2
    return 1
  fi
  echo "run-$1"
}

# Clones the e2e tests repo into a temp dir and prints the path (logs go to stderr, so the caller can
# capture it). The target branch is this repo's branch, which often does not exist in the e2e repo;
# then the fallback branch is used, with `develop` and `master` as last resorts. The fallback matters:
# a change based on `develop` needs the `develop` suite, not the `master` one.
clone_e2e_tests_repo() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: clone_e2e_tests_repo <target branch> <fallback branch>" >&2
    return 1
  fi

  local TARGET_BRANCH=$1
  local FALLBACK_BRANCH=$2
  local E2E_DIR
  E2E_DIR=$(mktemp -d) || return 2

  local CANDIDATES=("$TARGET_BRANCH")
  local BRANCH
  for BRANCH in "$FALLBACK_BRANCH" develop master; do
    [ -n "$BRANCH" ] && ! printf '%s\n' "${CANDIDATES[@]}" | grep -qxF "$BRANCH" && CANDIDATES+=("$BRANCH")
  done

  echo "" >&2
  for BRANCH in "${CANDIDATES[@]}"; do
    # A failed clone can leave files behind, and git refuses to clone into a non-empty dir.
    rm -rf "${E2E_DIR:?}" && mkdir -p "$E2E_DIR" || return 2
    if git clone --depth 1 --branch "$BRANCH" "$E2E_TESTS_REPO" "$E2E_DIR" >/dev/null 2>&1; then
      echo ">>> Cloned e2e tests repo (branch: $BRANCH) into $E2E_DIR" >&2
      echo "$E2E_DIR"
      return 0
    fi
    echo ">>> Branch '$BRANCH' not found in e2e repo" >&2
  done

  echo "ERROR: none of the e2e repo branches [${CANDIDATES[*]}] could be cloned" >&2
  return 3
}

# Makes the ROR KBN pre-build helpers callable here: dispatch_kbn_prebuild_image and
# wait_for_kbn_prebuild_image. They are owned by the e2e tests repo, so they come from its clone.
load_kbn_prebuild_helpers() {
  if [ "$#" -ne 1 ]; then
    echo "Usage: load_kbn_prebuild_helpers <e2e tests dir>"
    return 1
  fi

  local LIB="$1/$E2E_PREBUILD_IMAGES_LIB"
  if [ ! -f "$LIB" ]; then
    echo "ERROR: $E2E_PREBUILD_IMAGES_LIB not found in the e2e tests clone ($1)"
    echo "       The ROR KBN pre-build dispatch/poll helpers are owned by $E2E_TESTS_REPO."
    echo "       The checked-out e2e branch predates them — merge/rebase it so the file is present."
    return 2
  fi

  # shellcheck source=/dev/null
  . "$LIB" || return 3
}

# The ELK version an e2e module is tested at: the newest ES version that module supports, taken from
# the build, because each module's supportedEsVersions is the only source of truth. Gradle may print
# more than the version, so only the last line is used and it has to look like a version.
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

# The test matrix as GitHub Actions JSON: one entry per ES module, with its ELK version. Jobs are
# named after the module, so branch-protection checks survive a version bump.
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

# Passes the dispatched ROR KBN run (id and url) and the wait timeout to the test jobs. Without the
# run id they wait blind: a build that fails after two minutes would still cost every test job its
# full timeout. A no-op outside GitHub Actions.
# Usage: export_kbn_prebuild_run_info <version count>
export_kbn_prebuild_run_info() {
  [ -n "${GITHUB_OUTPUT:-}" ] || return 0

  # One run builds the versions one after another, so the last image can take N times longer to show
  # up than a single-version build. Scale the timeout with the number of versions.
  local WAIT_TIMEOUT=$(( ${1:-1} * ${ROR_KBN_WAIT_TIMEOUT_SECONDS:-1800} ))
  {
    echo "kbn_run_id=${ROR_KBN_PREBUILD_RUN_ID:-}"
    echo "kbn_run_url=${ROR_KBN_PREBUILD_RUN_URL:-}"
    echo "kbn_wait_timeout_seconds=$WAIT_TIMEOUT"
  } >> "$GITHUB_OUTPUT"
}

# Entry point for the `prepare_e2e_kbn_images` task. Runs once per pipeline run: resolves the ELK
# version of every e2e module, publishes the test matrix, and dispatches one ROR KBN pre-build for
# all of those versions.
# Args: <es modules> <target branch> <fallback branch> <build id>
#   es modules      — e2e ES modules, space- or comma-separated (e.g. "es94x es818x es717x")
#   target branch   — branch to build the ROR KBN plugin from (ROR_KBN_TARGET_BRANCH). The e2e repo
#                     is cloned from it too, but only to load the shared pre-build helpers
#   fallback branch — used for that clone when <target branch> is missing in the e2e repo; the ROR
#                     KBN pre-build workflow does its own fallback in its own repo
#   build id        — E2E_BUILD_ID; also published as the `build_id` output for the test jobs to reuse
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
  # The matrix the test jobs fan out over, and the build id whose tag the images dispatched below
  # will carry. A no-op outside GitHub Actions.
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "matrix=$MATRIX" >> "$GITHUB_OUTPUT"
    echo "build_id=$4" >> "$GITHUB_OUTPUT"
  fi

  echo ">>> Preparing e2e ROR KBN dev images: ELK [$ELK_VERSIONS], run tag: $RUN_TAG"

  # Cloned only for the dispatch helper; the suite itself runs from the test jobs' own clones.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # Non-blocking: the ROR KBN build runs remotely while the test jobs build their ROR ES images. It
  # is dispatched even when the image already exists, so the per-run tag always gets applied.
  dispatch_kbn_prebuild_image "$ELK_VERSIONS" "$TARGET_BRANCH" "$RUN_TAG" || return $?

  export_kbn_prebuild_run_info "$(echo "$MATRIX" | jq '.include | length')"
}

# Runs the Cypress suite from an already-cloned e2e tests repo, against this run's ROR ES and ROR KBN
# dev images (both carry the same run tag).
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

  # The limits overlays cap each Kibana replica at 1 GB, which the heavier specs exceed: the replica
  # is OOM-killed, never recovers, and every spec after it fails. They exist only for the small
  # (~8 GB) Azure host, so apply them only when the host is short on memory.
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

# Entry point for the `run_e2e_tests` task. Runs once per ELK version, after prepare_e2e_kbn_images
# has dispatched the ROR KBN build for it.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch to take the e2e suite from (E2E_TARGET_BRANCH)
#   fallback branch — branch this change is based on; used when <target branch> is missing in the
#                     e2e repo
#   build id        — E2E_BUILD_ID, as published by the preparing job. Never re-derive it here: it
#                     names the images that job actually dispatched
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

  # Checked before the clone, so a typo fails in seconds instead of after a network round trip.
  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ELK version format. Expected format: X.Y.Z"
    return 2
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # The e2e repo first: it provides both the wait helper and the test runner used below.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  load_kbn_prebuild_helpers "$E2E_DIR" || return $?

  # The suite writes videos and screenshots into a temp dir the caller cannot guess. Publish the path
  # so a later step can still collect them after this one failed. A no-op outside GitHub Actions.
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "E2E_TESTS_DIR=$E2E_DIR" >> "$GITHUB_ENV"
  fi

  # Build and publish the ROR ES dev image for this version, with the run tag as an alias. Gradle is
  # skipped when an image for the same sources already exists.
  publish_ror_es_prebuild_plugin "$ELK_VERSION" "$RUN_TAG" || return $?

  # Wait for this version's ROR KBN image. With the run id the wait can stop as soon as that build
  # fails; without it, it can only time out.
  if [ -z "${ROR_KBN_PREBUILD_RUN_ID:-}" ]; then
    echo ">>> ROR_KBN_PREBUILD_RUN_ID is not set: the poll below cannot stop early if the ROR KBN"
    echo "    pre-build run fails, and will instead use its whole timeout."
  fi
  wait_for_kbn_prebuild_image "$ELK_VERSION" "$RUN_TAG" || return $?

  # Both images are now available under the run tag.
  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
