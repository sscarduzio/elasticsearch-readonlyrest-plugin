#!/bin/bash -e

CI_DIR=$(dirname "$0")

# Setup git
git || (echo "FATAL: Failed to setup git!" && exit 1)
git remote set-url origin git@github.com:sscarduzio/elasticsearch-readonlyrest-plugin.git
git config --global push.default matching
git config --global user.email "builds@travis-ci.com"
git config --global user.name "Travis CI"

# TAGGING
function tag {
    GIT_TAG="$1"

    # Check if this tag already exists, so we don't overwrite existing builds
    __tag_exists $GIT_TAG && return 1

    echo "Tagging as $GIT_TAG"
    if [[ "$(uname -s)" == *"Linux"* ]]; then
        git tag $GIT_TAG -a -m "Generated tag from TravisCI build $TRAVIS_BUILD_NUMBER" &&
        git push origin $GIT_TAG &&
        return 0
    fi
    return 1
}

function __tag_exists {
    GIT_TAG="$1"
    if git pull && git tag --list | egrep -e "^${GIT_TAG}$" > /dev/null; then
        return 0
    fi
    return 1
}


function tag_delete {
    GIT_TAG="$1"
    if __tag_exists; then
      echo "No need to delete non-existing tag $GIT_TAG"
      return 0
    fi
    echo "Deleting tag $GIT_TAG"
    if [[ "$(uname -s)" == *"Linux"* ]]; then
        git tag -d $GIT_TAG && git push origin :refs/tags/$GIT_TAG || echo "Failed to delete tag $GIT_TAG"
        return 0
    fi
    return 1
}

file . || (echo "FATAL: file command does not work or is not installed!" && exit 1)
function upload {
  BUCKET="readonlyrest-data"
  LOCAL_FILE="$1"
  S3_PATH="$2"
  # shellcheck disable=SC2154
  "$CI_DIR"/s3-uploader.sh "$aws_access_key_id" "$aws_secret_access_key" "$BUCKET@eu-west-1" "$LOCAL_FILE" "$S3_PATH"
}
