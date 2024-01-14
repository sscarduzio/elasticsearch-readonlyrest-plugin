#!/bin/bash -e

CI_DIR=$(dirname "$0")

function checkTagNotExist {
  GIT_TAG="$1"

  # Check if this tag already exists, so we don't overwrite builds
  if git fetch --tags >/dev/null && git tag --list | egrep -e "^${GIT_TAG}$" >/dev/null; then
    echo "Git tag $GIT_TAG already exists, exiting."
    return 1
  fi
}

function tag {
  GIT_TAG="$1"

  checkTagNotExist "$GIT_TAG"

  echo "Tagging as $GIT_TAG"
  if [[ "$(uname -s)" == *"Linux"* ]]; then
#    git remote set-url origin git@github.com:sscarduzio/elasticsearch-readonlyrest-plugin.git
    git config --global push.default matching
    git config --global user.email "support@readonlyrest.com"
    git config --global user.name "Azure Pipeline"
    git tag $GIT_TAG -a -m "Generated tag from Azure Pipeline build $TRAVIS_BUILD_NUMBER"
    git push origin $GIT_TAG
  fi
  return 0
}

function upload {
  BUCKET="readonlyrest-data"
  LOCAL_FILE="$1"
  S3_PATH="$2"
  # shellcheck disable=SC2154
  "$CI_DIR"/s3-uploader.sh "$aws_access_key_id" "$aws_secret_access_key" "$BUCKET@eu-west-1" "$LOCAL_FILE" "$S3_PATH"
}
