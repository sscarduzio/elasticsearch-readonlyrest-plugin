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

function upload_to_ror_data_bucket {
  LOCAL_FILE="$1"
  S3_PATH="$2"
  
  BUCKET="readonlyrest-data"
  # shellcheck disable=SC2154
  "$CI_DIR"/s3-uploader.sh "$aws_access_key_id" "$aws_secret_access_key" "$BUCKET@eu-west-1" "$LOCAL_FILE" "$S3_PATH"
}

function upload_to_ror_data_xdelta_bucket {
  LOCAL_FILE="$1"
  S3_PATH=$(echo "$2" | sed 's:/*$::')
  FILE_NAME=$(basename $LOCAL_FILE)

  BUCKET="readonlyrest-data-xdelta"
  DELTA_GLIDER_VERSION="main-012662c"

  docker run --rm -e AWS_ACCESS_KEY_ID=$aws_access_key_id -e AWS_SECRET_ACCESS_KEY=$aws_secret_access_key \
    -v $LOCAL_FILE:/tmp/$FILE_NAME:ro \
    beshultd/deltaglider:$DELTA_GLIDER_VERSION \
    cp /tmp/$FILE_NAME s3://$BUCKET/${S3_PATH}/${FILE_NAME}
}