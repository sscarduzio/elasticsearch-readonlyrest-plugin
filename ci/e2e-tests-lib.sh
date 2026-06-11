# Sourced by run-pipeline.sh — do not execute directly.

# E2E tests helpers used by the `run_e2e_tests` task in run-pipeline.sh.
#
# The flow runs the Cypress e2e suite (docker env only) for a given ES version against per-run dev
# Docker images of both ROR plugins (ES + KBN):
#   1) Dispatch the ROR KBN dev image build in the KBN repo (non-blocking). The build is skip-optimized:
#      if sources are unchanged it only applies a cheap registry-side alias tag, so the wait is short.
#   2) Build & publish the ROR ES dev image from this repo (runs in parallel with step 1).
#   3) Wait until the per-run KBN image tag appears on Docker Hub, then run the e2e suite.
#
# Both images are tagged with a per-run tag (run-<BUILD_BUILDID>) so each pipeline run gets its own
# immutable refs. This prevents false hits from a previous run's image and makes concurrent runs safe.

E2E_KBN_REPO="sscarduzio/readonlyrest_kbn"
E2E_KBN_PUBLISH_WORKFLOW="publish-pre-builds.yml"
E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
E2E_KBN_DEV_IMAGE_REPO="beshultd/kibana-readonlyrest-dev"

# Dispatch the ROR KBN pre-build workflow for the given version and run tag. Does not wait for it.
# We always dispatch — even when the canonical image exists — so the per-run alias tag is guaranteed
# to exist by the time the poll in wait_for_kbn_prebuild_image succeeds. The workflow's skip
# optimization means a "no KBN changes" dispatch only does a cheap registry-side retag.
# target_branch may be a feature branch that doesn't exist in the KBN repo (e.g. on ES-only PRs);
# the KBN publish-pre-builds workflow falls back to develop for unknown branches.
dispatch_kbn_prebuild_image() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: dispatch_kbn_prebuild_image <kbn version> <target branch> <run tag>"
    return 1
  fi

  local KBN_VERSION=$1
  local TARGET_BRANCH=$2
  local RUN_TAG=$3

  if [ -z "${KBN_REPO_GH_TOKEN:-}" ]; then
    echo "ERROR: KBN_REPO_GH_TOKEN is not set (required to dispatch the ROR KBN pre-build workflow)"
    return 2
  fi

  echo ""
  echo ">>> Dispatching ROR KBN pre-build: version=$KBN_VERSION tag=$RUN_TAG branch=$TARGET_BRANCH"
  if ! GH_TOKEN="$KBN_REPO_GH_TOKEN" gh workflow run "$E2E_KBN_PUBLISH_WORKFLOW" \
        -R "$E2E_KBN_REPO" \
        -f "kbn_versions=$KBN_VERSION" \
        -f "target_branch=$TARGET_BRANCH" \
        -f "tag=$RUN_TAG"; then
    echo "ERROR: Failed to dispatch the ROR KBN pre-build workflow"
    return 3
  fi
  echo ">>> Dispatch sent"
}

# Poll Docker Hub until the per-run KBN image tag appears. Returns quickly when sources are
# unchanged (the workflow's skip path only does a cheap retag, typically a few minutes).
wait_for_kbn_prebuild_image() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: wait_for_kbn_prebuild_image <kbn version> <run tag>"
    return 1
  fi

  local KBN_VERSION=$1
  local RUN_TAG=$2
  local KBN_IMAGE="${E2E_KBN_DEV_IMAGE_REPO}:${KBN_VERSION}-ror-${RUN_TAG}"
  local WAIT_TIMEOUT_SECONDS=$((30 * 60))
  local POLL_INTERVAL_SECONDS=30
  local WAITED=0

  echo ""
  echo ">>> Polling for $KBN_IMAGE (timeout: $((WAIT_TIMEOUT_SECONDS / 60)) min)"
  while ! docker_image_exists "$KBN_IMAGE"; do
    if [ "$WAITED" -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "ERROR: Timed out after $((WAITED / 60)) min waiting for $KBN_IMAGE"
      echo "       Check the '$E2E_KBN_PUBLISH_WORKFLOW' run in $E2E_KBN_REPO."
      return 4
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    WAITED=$((WAITED + POLL_INTERVAL_SECONDS))
  done

  echo ">>> ROR KBN dev image is now available: $KBN_IMAGE"
}

# Clone the e2e tests repo and run the Cypress suite against dev images of both plugins.
# Both images are identified by the same run tag so the same per-run alias is passed to both sides.
run_e2e_against_dev_images() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: run_e2e_against_dev_images <elk version> <run tag> <target branch>"
    return 1
  fi

  local ELK_VERSION=$1
  local RUN_TAG=$2
  local TARGET_BRANCH=$3

  if [ -z "${ROR_ACTIVATION_KEY:-}" ]; then
    echo "ERROR: ROR_ACTIVATION_KEY is not set (required to run the e2e Cypress tests)"
    return 2
  fi

  local E2E_DIR
  E2E_DIR=$(mktemp -d)

  echo ""
  if ! git clone --depth 1 --branch "$TARGET_BRANCH" "$E2E_TESTS_REPO" "$E2E_DIR" 2>/dev/null; then
    echo ">>> Branch '$TARGET_BRANCH' not found in e2e repo, falling back to master"
    git clone --depth 1 --branch master "$E2E_TESTS_REPO" "$E2E_DIR"
  else
    echo ">>> Cloned e2e tests repo (branch: $TARGET_BRANCH) into $E2E_DIR"
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, image tag: $RUN_TAG"
  (
    cd "$E2E_DIR"
    ./runner.sh \
      --run e2e \
      --env docker \
      --elk "$ELK_VERSION" \
      --ror-es "$RUN_TAG" \
      --ror-kbn "$RUN_TAG" \
      --mode dev
  )
}

# Entry point for the `run_e2e_tests` task in run-pipeline.sh.
# Args: <elk version> <target branch> <build id>
#   elk version   — ELK version to test (X.Y.Z)
#   target branch — KBN branch to build from
#   build id      — E2E_BUILD_ID (Build.BuildId-System.JobAttempt); unique per attempt (run-<id>)
run_e2e_tests() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: run_e2e_tests <elk version> <target branch> <build id>"
    return 1
  fi

  local ELK_VERSION=$1
  local TARGET_BRANCH=$2
  local RUN_TAG="run-$3"

  if ! [[ $ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ELK version format. Expected format: X.Y.Z"
    return 2
  fi

  echo ">>> Running e2e tests: ELK $ELK_VERSION, run tag: $RUN_TAG"

  # Step 1: fire off the KBN build without blocking. Runs remotely while we build the ES image.
  dispatch_kbn_prebuild_image "$ELK_VERSION" "$TARGET_BRANCH" "$RUN_TAG"

  # Step 2: build & publish the ROR ES dev image (publish_ror_prebuild_plugin is defined in ci-lib.sh).
  # The skip optimization applies: if the sha-frozen image already exists, Gradle is not re-run.
  # The run tag is applied as an alias so the e2e runner can reference it.
  publish_ror_prebuild_plugin "$ELK_VERSION" "$RUN_TAG"

  # Step 3: block until the KBN image dispatched in step 1 appears (fast on the skip path).
  wait_for_kbn_prebuild_image "$ELK_VERSION" "$RUN_TAG"

  # Step 4: run the e2e suite against both dev images (now both available as <version>-ror-<RUN_TAG>).
  run_e2e_against_dev_images "$ELK_VERSION" "$RUN_TAG" "$TARGET_BRANCH"
}
