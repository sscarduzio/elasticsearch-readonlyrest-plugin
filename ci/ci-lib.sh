#!/bin/bash -e

CI_DIR=$(dirname "$0")

function checkTagNotExist {
  GIT_TAG="$1"

  # Check if this tag already exists, so we don't overwrite builds
  if git fetch --tags --quiet >/dev/null && git tag --list | egrep -e "^${GIT_TAG}$" >/dev/null; then
    echo "Git tag $GIT_TAG already exists, exiting."
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
  git tag "$GIT_TAG" -a -m "Generated tag from Azure Pipeline build $TRAVIS_BUILD_NUMBER"
  git push origin "$GIT_TAG"
  return 0
}

# not used at the moment - it may be needed laterok,
function upload_using_aws_s3_uploader {
  LOCAL_FILE="$1"
  S3_PATH="$2"

  STORE_ADDR="${ROR_ARTIFACTS_STORE_URL_OR_REGION:-}"
  if [[ "$STORE_ADDR" =~ ^https?:// ]]; then
    echo "ERROR: upload_using_aws_s3_uploader does not support URL endpoints; set ROR_ARTIFACTS_STORE_URL_OR_REGION to a region name (e.g. eu-west-1)"
    exit 1
  fi

  BUCKET="${ROR_ARTIFACTS_STORE_BUCKET:-ror-builds-xdelta}"
  REGION="${STORE_ADDR:-us-east-1}"
  PATH_PREFIX="${ROR_ARTIFACTS_STORE_PATH_PREFIX:-}"
  [ -n "$PATH_PREFIX" ] && PATH_PREFIX="${PATH_PREFIX%/}/"
  "$CI_DIR"/s3-uploader.sh "$ROR_ARTIFACTS_STORE_ACCESS_KEY_ID" "$ROR_ARTIFACTS_STORE_ACCESS_KEY_SECRET" "$BUCKET@$REGION" "$LOCAL_FILE" "${PATH_PREFIX}${S3_PATH}"
}

function upload_using_deltaglider_uploader {
  LOCAL_FILE="$1"
  S3_PATH=$(echo "$2" | sed 's:/*$::')
  FILE_NAME=$(basename "$LOCAL_FILE")

  STORE_ADDR="${ROR_ARTIFACTS_STORE_URL_OR_REGION:-}"
  if [[ ! "$STORE_ADDR" =~ ^https?:// ]]; then
    echo "ERROR: upload_using_deltaglider_uploader requires a URL endpoint; set ROR_ARTIFACTS_STORE_URL_OR_REGION to an http(s):// URL"
    exit 1
  fi

  BUCKET="${ROR_ARTIFACTS_STORE_BUCKET:-ror-builds-xdelta}"
  PATH_PREFIX="${ROR_ARTIFACTS_STORE_PATH_PREFIX:-}"
  [ -n "$PATH_PREFIX" ] && PATH_PREFIX="${PATH_PREFIX%/}/"
  DELTA_GLIDER_VERSION="6.1.1"

  docker run --rm \
    -e AWS_ENDPOINT_URL=$STORE_ADDR \
    -e AWS_ACCESS_KEY_ID=$ROR_ARTIFACTS_STORE_ACCESS_KEY_ID \
    -e AWS_SECRET_ACCESS_KEY=$ROR_ARTIFACTS_STORE_ACCESS_KEY_SECRET \
    -v "$LOCAL_FILE":"/tmp/$FILE_NAME":ro \
    beshultd/deltaglider:$DELTA_GLIDER_VERSION \
    cp "/tmp/$FILE_NAME" "s3://$BUCKET/${PATH_PREFIX}${S3_PATH}/${FILE_NAME}"
}