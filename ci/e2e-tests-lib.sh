# Sourced by run-pipeline.sh — do not execute directly.

# E2E tests helpers used by the `run_e2e_tests` task in run-pipeline.sh.
#
# The flow runs the Cypress e2e suite (docker env only) for a given ES version against per-run dev
# Docker images of both ROR plugins (ES + KBN):
#   0) Clone the e2e tests repo and source its ci/prebuild-images-lib.sh — the single, cross-repo
#      home of the pre-build dispatch/poll contract (see below). The suite in step 4 runs from this
#      same clone, so the clone is hoisted here rather than done at the end.
#   1) Dispatch the ROR KBN dev image build in the KBN repo (non-blocking). The build is skip-optimized:
#      if sources are unchanged it only applies a cheap registry-side alias tag, so the wait is short.
#   2) Build & publish the ROR ES dev image from this repo (runs in parallel with step 1).
#   3) Wait until the per-run KBN image tag appears on Docker Hub, then run the e2e suite.
#
# Both images are tagged with a per-run tag (run-<BUILD_BUILDID>) so each pipeline run gets its own
# immutable refs. This prevents false hits from a previous run's image and makes concurrent runs safe.
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

# Entry point for the `run_e2e_tests` task in run-pipeline.sh.
# Args: <elk version> <target branch> <fallback branch> <build id>
#   elk version     — ELK version to test (X.Y.Z)
#   target branch   — branch to build the KBN plugin from / to take the e2e suite from
#   fallback branch — branch this change is based on (PR base branch, or the pushed branch itself);
#                     used when <target branch> doesn't exist in the e2e repo
#   build id        — E2E_BUILD_ID (<run id>-<attempt>); unique per attempt (run-<id>)
run_e2e_tests() {
  if [ "$#" -ne 4 ]; then
    echo "Usage: run_e2e_tests <elk version> <target branch> <fallback branch> <build id>"
    return 1
  fi

  local ELK_VERSION=$1
  local TARGET_BRANCH=$2
  local FALLBACK_BRANCH=$3
  local RUN_TAG="run-$4"

  # Validate before the clone so a typo fails in seconds rather than after a network round trip
  # (the shared lib re-validates the version it is handed).
  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ELK version format. Expected format: X.Y.Z"
    return 2
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # Step 0: get the e2e repo first — it owns both the pre-build contract used by steps 1/3 and the
  # runner used by step 4.
  local E2E_DIR
  E2E_DIR=$(clone_e2e_tests_repo "$TARGET_BRANCH" "$FALLBACK_BRANCH") || return $?
  source_prebuild_images_lib "$E2E_DIR" || return $?

  # Step 1: fire off the KBN build without blocking. Runs remotely while we build the ES image.
  # We always dispatch — even when the canonical image exists — so the per-run alias tag is
  # guaranteed to exist by the time the step 3 poll succeeds.
  dispatch_kbn_prebuild_image "$ELK_VERSION" "$TARGET_BRANCH" "$RUN_TAG" || return $?

  # Step 2: build & publish the ROR ES dev image (publish_ror_prebuild_plugin is defined in ci-lib.sh).
  # The skip optimization applies: if the sha-frozen image already exists, Gradle is not re-run.
  # The run tag is applied as an alias so the e2e runner can reference it.
  publish_ror_prebuild_plugin "$ELK_VERSION" "$RUN_TAG" || return $?

  # Step 3: block until the KBN image dispatched in step 1 appears (fast on the skip path).
  wait_for_kbn_prebuild_image "$ELK_VERSION" "$RUN_TAG" || return $?

  # Step 4: run the e2e suite against both dev images (now both available as <version>-ror-<RUN_TAG>).
  run_e2e_against_dev_images "$E2E_DIR" "$ELK_VERSION" "$RUN_TAG"
}
