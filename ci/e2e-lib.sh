#!/bin/bash -e

# E2E tests helpers used by the `run_e2e_tests` task in run-pipeline.sh.
#
# The flow runs the Cypress e2e suite (docker env only) for a given ES version against the dev Docker
# images of both ROR plugins (ES + KBN):
#   1) make sure the matching ROR KBN dev image exists on Docker Hub (order its build and wait if missing)
#   2) build & publish the ROR ES dev image from this repo (done by the caller via public_ror_prebuild_plugin)
#   3) clone the e2e tests repo and run the suite against an env that uses both dev images.

# Repos used by the e2e flow
E2E_KBN_REPO="sscarduzio/readonlyrest_kbn"
E2E_KBN_PUBLISH_WORKFLOW="publish-pre-builds.yml"
E2E_TESTS_REPO="https://github.com/beshu-tech/readonlyrest-e2e-tests.git"
E2E_KBN_DEV_IMAGE_REPO="beshultd/kibana-readonlyrest-dev"
E2E_ES_DEV_IMAGE_REPO="coutopl/elasticsearch-readonlyrest-dev"

# Checks whether an image tag exists in the remote registry (Docker Hub). `docker manifest inspect`
# queries the registry over the network without pulling the image; it does NOT read the local image cache.
docker_image_exists() {
  docker manifest inspect "$1" >/dev/null 2>&1
}

# Order the ROR KBN dev Docker image build (if it is not already on Docker Hub) and return immediately,
# WITHOUT waiting for it to finish. The build runs remotely in the ROR KBN repo's GitHub Actions, so the
# caller can do other work (e.g. build the ES image) in parallel and call wait_for_kbn_prebuild_image later.
order_kbn_prebuild_image() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: order_kbn_prebuild_image <kbn version> <ror version> <target branch>"
    return 1
  fi

  local KBN_VERSION=$1
  local ROR_VERSION=$2
  local TARGET_BRANCH=$3
  local KBN_IMAGE="${E2E_KBN_DEV_IMAGE_REPO}:${KBN_VERSION}-ror-${ROR_VERSION}"

  echo ""
  echo ">>> Ensuring ROR KBN dev image is available: $KBN_IMAGE"

  if docker_image_exists "$KBN_IMAGE"; then
    echo ">>> ROR KBN dev image already present in Docker Hub, no build needed"
    return 0
  fi

  if [ -z "$KBN_REPO_GH_TOKEN" ]; then
    echo "ERROR: ROR KBN dev image is missing and KBN_REPO_GH_TOKEN is not set, cannot order the build"
    return 2
  fi

  echo ">>> ROR KBN dev image is missing, ordering its build ($E2E_KBN_REPO / $E2E_KBN_PUBLISH_WORKFLOW, branch: $TARGET_BRANCH)"
  if ! GH_TOKEN="$KBN_REPO_GH_TOKEN" gh workflow run "$E2E_KBN_PUBLISH_WORKFLOW" \
        -R "$E2E_KBN_REPO" \
        -f "kbn_versions=$KBN_VERSION" \
        -f "target_branch=$TARGET_BRANCH"; then
    echo "ERROR: Failed to dispatch the ROR KBN pre-build workflow"
    return 3
  fi
}

# Wait until the given ROR KBN dev image shows up on Docker Hub (it may be building in parallel). Returns
# immediately if the image is already present.
wait_for_kbn_prebuild_image() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: wait_for_kbn_prebuild_image <kbn image ref> <ror version>"
    return 1
  fi

  local KBN_IMAGE=$1
  local ROR_VERSION=$2
  local WAIT_TIMEOUT_SECONDS=$((15 * 60))
  local POLL_INTERVAL_SECONDS=30
  local WAITED=0

  echo ""
  echo ">>> Waiting for $KBN_IMAGE to be available in Docker Hub (timeout: $((WAIT_TIMEOUT_SECONDS / 60)) min)"
  while ! docker_image_exists "$KBN_IMAGE"; do
    if [ "$WAITED" -ge "$WAIT_TIMEOUT_SECONDS" ]; then
      echo "ERROR: Timed out after $((WAITED / 60)) min waiting for $KBN_IMAGE."
      echo "       Check the '$E2E_KBN_PUBLISH_WORKFLOW' run in $E2E_KBN_REPO."
      echo "       Note: the ROR KBN pluginVersion on that branch must match ROR version $ROR_VERSION."
      return 4
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    WAITED=$((WAITED + POLL_INTERVAL_SECONDS))
  done

  echo ">>> ROR KBN dev image is now available: $KBN_IMAGE"
}

# Build & publish the ROR ES dev image from this repo and make it available under a commit-specific tag.
# We tag it with the commit SHA so each commit gets its own immutable image: this guarantees we test the
# current code (a stable pluginVersion tag would be reused/clobbered across commits and parallel runs)
# while still letting same-commit re-runs reuse the already-built image. The build itself is done by
# public_ror_prebuild_plugin (defined in ci-lib.sh), which pushes the pluginVersion-tagged manifest; we
# then retag that manifest registry-side to the commit-specific tag with `docker buildx imagetools create`.
ensure_ror_es_dev_image() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: ensure_ror_es_dev_image <elk version> <ror version> <ror-es image version>"
    return 1
  fi

  local ELK_VERSION=$1
  local ROR_VERSION=$2
  local ROR_ES_VERSION=$3
  local ES_IMAGE="${E2E_ES_DEV_IMAGE_REPO}:${ELK_VERSION}-ror-${ROR_ES_VERSION}"

  echo ""
  echo ">>> Ensuring ROR ES dev image is available: $ES_IMAGE"
  if docker_image_exists "$ES_IMAGE"; then
    echo ">>> ROR ES dev image for this commit already present in Docker Hub, skipping build"
    return 0
  fi

  public_ror_prebuild_plugin "$ELK_VERSION"
  echo ">>> Re-tagging ROR ES dev image with commit SHA: $ES_IMAGE"
  docker buildx imagetools create \
    -t "$ES_IMAGE" \
    "${E2E_ES_DEV_IMAGE_REPO}:${ELK_VERSION}-ror-${ROR_VERSION}"
}

# Clone the e2e tests repo and run the Cypress suite against an environment that uses the dev images of
# both ROR plugins (ES + KBN) for the given version.
run_e2e_against_dev_images() {
  if [ "$#" -ne 3 ]; then
    echo "Usage: run_e2e_against_dev_images <elk version> <ror-es image version> <ror-kbn image version>"
    return 1
  fi

  local ELK_VERSION=$1
  local ROR_ES_VERSION=$2
  local ROR_KBN_VERSION=$3

  if [ -z "$ROR_ACTIVATION_KEY" ]; then
    echo "ERROR: ROR_ACTIVATION_KEY is not set (required to run the e2e Cypress tests)"
    return 2
  fi

  local E2E_DIR
  E2E_DIR=$(mktemp -d)

  echo ""
  echo ">>> Cloning e2e tests repo into $E2E_DIR"
  git clone --depth 1 "$E2E_TESTS_REPO" "$E2E_DIR"

  echo ">>> Running e2e tests (docker env, dev images) for ELK $ELK_VERSION (ror-es $ROR_ES_VERSION, ror-kbn $ROR_KBN_VERSION)"
  (
    cd "$E2E_DIR"
    ./runner.sh \
      --run e2e \
      --env docker \
      --elk "$ELK_VERSION" \
      --ror-es "$ROR_ES_VERSION" \
      --ror-kbn "$ROR_KBN_VERSION" \
      --mode dev
  )
}

# Entry point for the `run_e2e_tests` task. Orchestrates the three steps described at the top of this file.
# Depends on `public_ror_prebuild_plugin` (defined in ci-lib.sh) to build & publish the ES dev image.
run_e2e_tests() {
  if ! [[ $E2E_ELK_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid E2E_ELK_VERSION format. Expected format: X.Y.Z"
    return 1
  fi

  local TARGET_BRANCH="${E2E_TARGET_BRANCH:-develop}"
  local ROR_VERSION
  ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  echo ">>> Running e2e tests for ELK $E2E_ELK_VERSION (ROR $ROR_VERSION)"

  local KBN_IMAGE="${E2E_KBN_DEV_IMAGE_REPO}:${E2E_ELK_VERSION}-ror-${ROR_VERSION}"

  # 1) Order the ROR KBN dev image build up-front but do NOT block on it. KBN does not change per ES
  #    commit, so we use the plain pluginVersion-tagged shared pre-build. The build runs remotely, so it
  #    proceeds in parallel while we build the ES image below.
  order_kbn_prebuild_image "$E2E_ELK_VERSION" "$ROR_VERSION" "$TARGET_BRANCH"

  # 2) Build & publish the ROR ES dev image from this repo (this fills the KBN build window). It is tagged
  #    with the commit SHA so each commit gets its own immutable image (see ensure_ror_es_dev_image).
  local ROR_ES_VERSION="${ROR_VERSION}-$(git rev-parse --short HEAD)"
  ensure_ror_es_dev_image "$E2E_ELK_VERSION" "$ROR_VERSION" "$ROR_ES_VERSION"

  # 3) Now block until the KBN image (ordered in step 1) is available on Docker Hub
  wait_for_kbn_prebuild_image "$KBN_IMAGE" "$ROR_VERSION"

  # 4) Run the e2e tests against the env built from both dev images
  run_e2e_against_dev_images "$E2E_ELK_VERSION" "$ROR_ES_VERSION" "$ROR_VERSION"
}
