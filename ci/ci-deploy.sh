#!/bin/bash

source "$(dirname "$0")/ci-lib.sh"

###############################
# S3 Artifact Uploader Script #
###############################
# Motivation: upload features in specific CI providers (Travis, Azure,..) are a complete loss of time
# We use curl and that's it. Our scripts are way more portable now, and could run in our laptop if needed.
#
# This tool uploads builds only if that build does not have a corresponding tag in Git.
# For example, if tag "v1.42.0_es8.3.3" exists in Git, we won't upload the build 'readonlyrest_1.42.0_es8.3.3.zip' to S3.
#
# In practice, when you bump the version in build.gradle, this script tags and uploads only if necessary.

# Translate Azure to Travis env vars

export BRANCH_CI_BUILDING_FROM=$(git symbolic-ref --short -q HEAD)

if [ -nz ${BUILD_SOURCEBRANCHNAME:+x} ]
then
 export TRAVIS=true
 export BRANCH_CI_BUILDING_FROM=$BUILD_SOURCEBRANCHNAME
 export TRAVIS_BUILD_NUMBER="$BUILD_BUILDNUMBER"
fi
echo ">> FOUND CI PARAMETERS: task? $ROR_TASK; is CI? $TRAVIS; branch? $BRANCH_CI_BUILDING_FROM"


if [ ! -z "$TRAVIS_TAG" ]; then
  echo "Don't try to tag in response on a tag event"
  exit 0
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $BRANCH_CI_BUILDING_FROM != "master" ]]; then
    echo ">>> won't try to tag and upload builds because this is a PR"
    exit 0
fi

echo "Entering release uploader.."

function processBuild {
    PLUGIN_FILE="$1"
    echo "PLUGIN_FILE: $PLUGIN_FILE"
    DISTRIBUTION_PATH=$(dirname "$PLUGIN_FILE")
    PLUGIN_FILE_BASE=$(basename "$PLUGIN_FILE")

    ES_VERSION=$(echo $PLUGIN_FILE_BASE | awk -F "_es" {'print $2'} | sed "s/\.zip//")
    echo "ES_VERSION: $ES_VERSION"

    PLUGIN_VERSION=$(echo $PLUGIN_FILE_BASE | awk -F "_es" {'print $1'} | awk -F "readonlyrest-" {'print $2'})
    echo "PLUGIN_VERSION: $PLUGIN_VERSION"

    GIT_TAG=v$(echo $PLUGIN_FILE_BASE | sed 's/readonlyrest-//' | sed 's/\.zip//')
    echo "GIT_TAG: $GIT_TAG"
    tag $GIT_TAG || (echo "Failed tagging $GIT_TAG" && return 1)

    echo "Tagging OK, will upload to S3..."
    upload  $PLUGIN_FILE        build/$PLUGIN_VERSION/$PLUGIN_FILE_BASE &&
    upload  $PLUGIN_FILE.sha1   build/$PLUGIN_VERSION/$PLUGIN_FILE_BASE.sha1
    if [ $? == 0 ] ; then
      echo "Upload OK, build $PLUGIN_FILE_BASE processed successfully"
      return 0
    else
      echo "FATAL: upload $PLUGIN_FILE_BASE failed, removing tag $GIT_TAG and exiting..."
      tag_delete $GIT_TAG || echo "Failed to delete tag $GIT_TAG"
      exit 1
    fi
}

for zipFile in `ls -1 es*x/build/distributions/*zip`; do
    echo "Processing $zipFile ..."
    processBuild $zipFile
done

exit 0