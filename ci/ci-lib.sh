#!/bin/bash -e

CI_DIR=$(dirname "$0")

function docker_image_exists {
  docker manifest inspect "$1" >/dev/null 2>&1
}

# Repo of the ROR ES pre-build dev image. Must match each module's `preBuildDockerImageVersion` repo in
# es<ver>x/build.gradle (that is where Gradle actually pushes the canonical <esVersion>-ror-<pluginVersion>).
ES_DEV_IMAGE_REPO="beshultd/elasticsearch-readonlyrest-dev"

# Copies a registry image manifest to a new tag without pulling/rebuilding (multi-platform safe).
function retag_dev_image {
  if [ "$#" -ne 2 ]; then
    echo "Usage: retag_dev_image <source tag> <target tag>"
    return 1
  fi

  local SOURCE_TAG=$1
  local TARGET_TAG=$2
  echo ">>> Tagging ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG} as ${ES_DEV_IMAGE_REPO}:${TARGET_TAG}"
  docker buildx imagetools create \
    -t "${ES_DEV_IMAGE_REPO}:${TARGET_TAG}" \
    "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"
}

# Build & publish the ROR ES pre-build Docker image for the given ES version.
#
# To avoid rebuilding when the sources have not changed, every build is frozen under an immutable,
# source-identified tag <esVersion>-ror-<gitShortSha>. Before building we probe that tag in the registry:
# if it already exists the Gradle build (the expensive build+push) is skipped. Setting FORCE_REBUILD=true
# bypasses the skip.
#
# Tags produced (all but the Gradle push are cheap registry-side manifest copies):
#   - <esVersion>-ror-<pluginVersion>   canonical "latest", pushed by Gradle (only on a real build)
#   - <esVersion>-ror-<gitShortSha>     immutable source identity, frozen from canonical (probed for the skip)
#   - <esVersion>-ror-<imageTag>        optional alias to the source image, when an image tag arg is given
function publish_ror_prebuild_plugin {
  if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: publish_ror_prebuild_plugin <ES version> [image tag]"
    return 1
  fi

  local ES_VERSION=$1
  local IMAGE_TAG="${2:-}"

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ES version format. Expected format: X.Y.Z"
    return 2
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  local ROR_VERSION GIT_SHA
  ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')
  GIT_SHA=$(git rev-parse --short HEAD)

  local CANONICAL_TAG="${ES_VERSION}-ror-${ROR_VERSION}"
  local SOURCE_TAG="${ES_VERSION}-ror-${GIT_SHA}"

  echo ""
  echo "PUBLISHING ROR PRE-BUILD for ES $ES_VERSION (source ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}):"

  # Azure boolean params expand as True/False, so normalize case before comparing.
  local FORCE_REBUILD_NORM
  FORCE_REBUILD_NORM=$(echo "${FORCE_REBUILD:-false}" | tr '[:upper:]' '[:lower:]')

  if [ "$FORCE_REBUILD_NORM" != "true" ] && docker_image_exists "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"; then
    echo ">>> Sources unchanged (image for this commit already published), skipping build"
  else
    if ! ./gradlew publishEsRorPreBuildDockerImage "-PesVersion=$ES_VERSION" </dev/null; then
      echo "Failed to publish plugin prebuild Docker image"
      return 4
    fi
    # Freeze this build under its immutable source-identity tag so future runs can detect & skip it.
    if ! retag_dev_image "$CANONICAL_TAG" "$SOURCE_TAG"; then
      echo "Failed to tag prebuild Docker image as ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"
      return 5
    fi
  fi

  # Apply the optional caller-supplied alias on BOTH paths (built or skipped). The caller fetches the image
  # by this tag, so it must exist and point at this commit's image: we always derive it from SOURCE_TAG
  # (the commit identity), and the imagetools exit status guarantees it was created before we return.
  if [ -n "$IMAGE_TAG" ]; then
    if ! retag_dev_image "$SOURCE_TAG" "${ES_VERSION}-ror-${IMAGE_TAG}"; then
      echo "Failed to tag prebuild Docker image as ${ES_DEV_IMAGE_REPO}:${ES_VERSION}-ror-${IMAGE_TAG}"
      return 6
    fi
  fi
}

function checkTagNotExist {
  GIT_TAG="$1"

  # Check only the remote to avoid false positives from stale local tags left by a
  # previous attempt that created the local tag but failed before pushing it.
  if git ls-remote --tags origin "refs/tags/${GIT_TAG}" 2>/dev/null | grep -q "${GIT_TAG}"; then
    echo "Git tag $GIT_TAG already exists on remote, exiting."
    return 1
  fi
}

function tag {
  GIT_TAG="$1"

  checkTagNotExist "$GIT_TAG"

  echo "Tagging as $GIT_TAG"
  git config --global push.default matching
  git config --global user.email "support@readonlyrest.com"
  git config --global user.name "Azure Pipeline"
  # -f overwrites any stale local tag from a previous failed push attempt
  git tag -fa "$GIT_TAG" -m "Generated tag from Azure Pipeline build $TRAVIS_BUILD_NUMBER"
  git push origin "$GIT_TAG"
  return 0
}

# Upload a file to an S3-compatible store using the SigV4 curl uploader.
#
# The store is selected by the 3rd arg (default ARTIFACTS) and resolves the matching
# ROR_<STORE>_STORE_* env vars, so the same logic serves both the artifacts store
# (ROR_ARTIFACTS_STORE_*) and the libs store (ROR_LIBS_STORE_*). Each store keeps its
# own endpoint, credentials, bucket, region and path-prefix.
function upload_using_aws_s3_uploader {
  local LOCAL_FILE="$1"
  local S3_PATH STORE BUCKET PATH_PREFIX
  S3_PATH=$(echo "$2" | sed 's:/*$::')
  STORE="${3:-ARTIFACTS}"

  if [[ ! -f "$LOCAL_FILE" ]]; then
    echo "ERROR: artifact to upload not found (or not a regular file): $LOCAL_FILE"
    exit 1
  fi

  # Indirectly resolve the store-specific env vars (e.g. ROR_LIBS_STORE_BUCKET).
  local ENDPOINT_VAR="ROR_${STORE}_STORE_ENDPOINT_URL"
  local AK_VAR="ROR_${STORE}_STORE_ACCESS_KEY_ID"
  local SK_VAR="ROR_${STORE}_STORE_ACCESS_KEY_SECRET"
  local BUCKET_VAR="ROR_${STORE}_STORE_BUCKET"
  local REGION_VAR="ROR_${STORE}_STORE_REGION"
  local PREFIX_VAR="ROR_${STORE}_STORE_PATH_PREFIX"

  local ENDPOINT="${!ENDPOINT_VAR-}"
  local AK="${!AK_VAR-}"
  local SK="${!SK_VAR-}"
  local REGION="${!REGION_VAR-}"
  BUCKET="${!BUCKET_VAR-}"; BUCKET="${BUCKET:-beshu}"
  PATH_PREFIX="${!PREFIX_VAR-}"
  [ -n "$PATH_PREFIX" ] && PATH_PREFIX="${PATH_PREFIX%/}/"

  S3_ENDPOINT_URL="$ENDPOINT" \
    "$CI_DIR"/s3-uploader.sh \
      "$AK" "$SK" \
      "$BUCKET@${REGION:-us-east-1}" "$LOCAL_FILE" "${PATH_PREFIX}${S3_PATH}/"
}