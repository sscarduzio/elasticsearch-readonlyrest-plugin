# Sourced by run-pipeline.sh — do not execute directly.

# E2E tests helpers used by the `prepare_e2e_kbn_images` and `run_e2e_tests` tasks in
# run-pipeline.sh.
#
# The flow runs the Cypress e2e suite (docker env only) against per-run dev Docker images of both
# ROR plugins (ES + KBN), and is split over two CI jobs:
#
#   prepare_e2e_kbn_images — ONCE per pipeline run, for every ES version in the e2e matrix:
#     0) Clone the e2e tests repo and source its ci/prebuild-images-lib.sh — the single, cross-repo
#        home of the pre-build dispatch/poll contract (see below).
#     1) Dispatch ONE ROR KBN dev image build in the KBN repo, for the whole version list
#        (non-blocking). The build is skip-optimized: if sources are unchanged it only applies a
#        cheap registry-side alias tag, so the wait in the test jobs is short.
#
#   run_e2e_tests — once per ES version, in parallel, downstream of the above:
#     2) Clone the e2e tests repo (the suite runs from it) and source the shared lib again.
#     3) Build & publish the ROR ES dev image for THIS version from this repo. This is the expensive
#        step and the reason it stays per-version: the versions build in parallel, on separate
#        runners, while the single KBN run proceeds remotely.
#     4) Wait until this version's KBN image tag appears on Docker Hub.
#     5) Run the Cypress suite against both dev images.
#
# The dispatch is deliberately NOT per-version. The KBN pre-build workflow takes a version list, so
# one run covers the matrix; N dispatches would also make _locate_prebuild_run ambiguous — it picks
# the newest run of that workflow started since the dispatch, so N jobs dispatching within seconds
# of each other can each latch onto a sibling's run and mis-report its outcome.
#
# Both images are tagged with a per-run tag (run-<E2E_BUILD_ID>) so each pipeline run gets its own
# immutable refs. This prevents false hits from a previous run's image and makes concurrent runs safe.
# Both jobs derive that tag from the same build id (e2e_run_tag), so no value has to be passed
# between them.
#
# dispatch_kbn_prebuild_image / wait_for_kbn_prebuild_image are NOT defined here: the same pair is
# needed by the ROR KBN repo (mirrored) and by the e2e repo (both plugins), so they live once in
# readonlyrest-e2e-tests' ci/prebuild-images-lib.sh and are sourced from the clone. What stays
# repo-specific — and therefore stays here — is building OUR plugin image, cloning the e2e repo and
# invoking its runner.
#
# Note: the shared lib also defines docker_image_exists, identically to ci-lib.sh's. Sourcing it
# redefines ci-lib.sh's copy for the rest of the process; the bodies match, so this is a no-op.

E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
# Path of the shared pre-build contract WITHIN the e2e tests clone.
E2E_PREBUILD_IMAGES_LIB="ci/prebuild-images-lib.sh"

# The per-run image tag both jobs use. Derived from the build id in one place so the preparing job
# and the test jobs cannot disagree about which images belong to this run.
e2e_run_tag() {
  if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: e2e_run_tag <build id>" >&2
    return 1
  fi
  echo "run-$1"
}

# Clone the e2e tests repo into a fresh temp dir and echo the path (all logging goes to stderr so
# the caller can capture the path from stdout).
# target_branch may be a feature branch that doesn't exist in the e2e repo (e.g. on ES-only PRs).
# In that case fall back to the branch this change is based on (the PR's base branch, or the pushed
# branch itself) — a PR against `develop` must take the e2e suite from `develop`, not from `master`,
# because the two lines can be out of sync (e.g. the shared prebuild-images lib exists on one only).
# `master` stays as the last-resort candidate so a missing base branch never sinks the whole run.
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
  for BRANCH in "$FALLBACK_BRANCH" master; do
    [ -n "$BRANCH" ] && ! printf '%s\n' "${CANDIDATES[@]}" | grep -qxF "$BRANCH" && CANDIDATES+=("$BRANCH")
  done

  echo "" >&2
  for BRANCH in "${CANDIDATES[@]}"; do
    # A failed clone can leave partial content behind; git refuses to clone into a non-empty dir.
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

# Source the shared pre-build dispatch/poll helpers out of the e2e tests clone. After this returns,
# dispatch_kbn_prebuild_image and wait_for_kbn_prebuild_image are available with the signatures this
# file used to define locally.
source_prebuild_images_lib() {
  if [ "$#" -ne 1 ]; then
    echo "Usage: source_prebuild_images_lib <e2e tests dir>"
    return 1
  fi

  local LIB="$1/$E2E_PREBUILD_IMAGES_LIB"
  if [ ! -f "$LIB" ]; then
    echo "ERROR: $E2E_PREBUILD_IMAGES_LIB not found in the e2e tests clone ($1)"
    echo "       The pre-build dispatch/poll helpers are owned by $E2E_TESTS_REPO."
    echo "       The checked-out e2e branch predates them — merge/rebase it so the file is present."
    return 2
  fi

  # shellcheck source=/dev/null
  . "$LIB" || return 3
}

# The ELK version an e2e module is tested at: the newest ES version that module supports, straight
# from the build (each module's supportedEsVersions is the single source of truth — never re-derive
# it here). Gradle's stdout can carry build-script logging even under --quiet, so take the last
# non-empty line and insist it looks like a version rather than trusting the whole output.
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

# The e2e job matrix as GitHub Actions matrix JSON: one entry per module, carrying the module name
# (stable job names, so branch-protection checks survive a version bump) and its ELK version.
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

# Hand the dispatched KBN run's coordinates, and the poll timeout its shape implies, to the
# downstream test jobs — separate processes on separate machines, which would otherwise poll blind:
# without a run id, a KBN build that fails in two minutes costs every test job its full poll timeout
# instead of failing immediately. The shared lib seeds ROR_KBN_PREBUILD_RUN_ID/_URL and
# ROR_KBN_WAIT_TIMEOUT_SECONDS from the environment, so the test jobs only have to export what is
# written here. A no-op outside GitHub Actions.
# Usage: export_kbn_prebuild_run_info <version count>
export_kbn_prebuild_run_info() {
  [ -n "${GITHUB_OUTPUT:-}" ] || return 0

  # One run builds the versions one after another, so the last image can appear roughly N× later
  # than it would have when every version had a run of its own. Scale the wait with the count, off
  # the shared lib's single-version default.
  local WAIT_TIMEOUT=$(( ${1:-1} * ${ROR_KBN_WAIT_TIMEOUT_SECONDS:-1800} ))
  {
    echo "kbn_run_id=${ROR_KBN_PREBUILD_RUN_ID:-}"
    echo "kbn_run_url=${ROR_KBN_PREBUILD_RUN_URL:-}"
    echo "kbn_wait_timeout_seconds=$WAIT_TIMEOUT"
  } >> "$GITHUB_OUTPUT"
}

# Entry point for the `prepare_e2e_kbn_images` task in run-pipeline.sh. Runs once per pipeline run.
# Resolves the ELK version of every e2e module, publishes the resulting test matrix, and dispatches
# a single KBN pre-build covering all of them.
# Args: <es modules> <target branch> <fallback branch> <build id>
#   es modules      — space- or comma-separated e2e ES modules (e.g. "es94x es818x es717x")
#   target branch   — branch to build the KBN plugin from
#   fallback branch — branch this change is based on; used when <target branch> doesn't exist in the
#                     e2e repo (the KBN pre-build workflow does its own fallback for its own repo)
#   build id        — E2E_BUILD_ID (<run id>-<attempt>); unique per attempt
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
  # The matrix the test jobs fan out over. A no-op outside GitHub Actions.
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "matrix=$MATRIX" >> "$GITHUB_OUTPUT"
  fi

  echo ">>> Preparing e2e KBN dev images: ELK [$ELK_VERSIONS], run tag: $RUN_TAG"

  # The e2e repo owns the dispatch contract; clone it just for that (the suite itself runs from the
  # test jobs' own clones).
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  source_prebuild_images_lib "$E2E_DIR" || return $?

  # One dispatch for every version, and non-blocking: the KBN build runs remotely while the test
  # jobs build their ES images. We always dispatch — even when the canonical image exists — so the
  # per-run alias tag is guaranteed to exist by the time a test job's poll succeeds.
  dispatch_kbn_prebuild_image "$ELK_VERSIONS" "$TARGET_BRANCH" "$RUN_TAG" || return $?

  export_kbn_prebuild_run_info "$(echo "$MATRIX" | jq '.include | length')"
}

# Run the Cypress suite from an already-cloned e2e tests repo, against dev images of both plugins.
# Both images are identified by the same run tag so the same per-run alias is passed to both sides.
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

  # The *.limits.docker-compose.yml overlays cap each Kibana replica at mem_limit=1g with NO container
  # swap, while Kibana runs with --max-old-space-size=768. Under the heavier feature specs (Reporting,
  # Settings, Spaces, Tenancy, Saved-objects, …) a replica's RSS crosses 1g, the cgroup OOM-kills it,
  # and `restart: always` sends it into a cold-boot crash-loop it never recovers from mid-run — so
  # every spec after the first heavy one fails. Those limits exist only for the small ~7.9 GB Azure
  # host; apply them ONLY on memory-constrained hosts and let the pinned heaps govern everywhere else
  # (the 16 GB Ubicloud CI runner has ample headroom for the ~5 GB the unconstrained stack uses).
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

# Entry point for the `run_e2e_tests` task in run-pipeline.sh. Runs once per ES version, downstream
# of prepare_e2e_kbn_images — the KBN build for this version was already dispatched there.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch to take the e2e suite from
#   fallback branch — branch this change is based on (PR base branch, or the pushed branch itself);
#                     used when <target branch> doesn't exist in the e2e repo
#   build id        — E2E_BUILD_ID (<run id>-<attempt>); must be the one the preparing job used, as
#                     it identifies this run's images
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

  # Validate before the clone so a typo fails in seconds rather than after a network round trip
  # (the shared lib re-validates the version it is handed).
  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ELK version format. Expected format: X.Y.Z"
    return 2
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # Step 2: get the e2e repo first — it owns both the poll contract used by step 4 and the runner
  # used by step 5.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  source_prebuild_images_lib "$E2E_DIR" || return $?

  # The suite writes its videos/screenshots under $E2E_DIR/results, and that path is a mktemp dir the
  # caller cannot guess. Publish it so a later step can still collect them once this one has failed.
  # A no-op outside GitHub Actions.
  if [ -n "${GITHUB_ENV:-}" ]; then
    echo "E2E_TESTS_DIR=$E2E_DIR" >> "$GITHUB_ENV"
  fi

  # Step 3: build & publish the ROR ES dev image (publish_ror_prebuild_plugin is defined in ci-lib.sh).
  # The skip optimization applies: if the sha-frozen image already exists, Gradle is not re-run.
  # The run tag is applied as an alias so the e2e runner can reference it.
  publish_ror_prebuild_plugin "$ELK_VERSION" "$RUN_TAG" || return $?

  # Step 4: block until this version's KBN image appears (fast on the skip path). The poll fast-fails
  # on a failed KBN build only if ROR_KBN_PREBUILD_RUN_ID was exported from the preparing job.
  if [ -z "${ROR_KBN_PREBUILD_RUN_ID:-}" ]; then
    echo ">>> ROR_KBN_PREBUILD_RUN_ID is not set: the poll below cannot stop early if the KBN"
    echo "    pre-build run fails, and will instead use its whole timeout."
  fi
  wait_for_kbn_prebuild_image "$ELK_VERSION" "$RUN_TAG" || return $?

  # Step 5: run the e2e suite against both dev images (now both available as <version>-ror-<RUN_TAG>).
  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
