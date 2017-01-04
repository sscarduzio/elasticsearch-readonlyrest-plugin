#!/bin/bash

###############################
# S3 Artivact Uploader Script #
###############################
# Motivation: "artifact" addon and deploy feature of Travis are a complete loss of time
# The S3 command line tool is this: https://github.com/pivotal-golang/s3cli

CONF_FILE="conf.json"
BUCKET="redirector-readonlyrest.com"

# The AWS Credentials are in env vars, no need to write them here.

echo "Entering release uploader.."

echo "PWD: $PWD"

if [[ "$TRAVIS_BRANCH" != "auto-build" ]]; then
    echo "Nothing to do for branch $TRAVIS_BRANCH"
    exit 0
fi

PLUGIN_FILE=$(echo build/distributions/readonlyrest-v*.zip)
echo "PLUGIN_FILE: $PLUGIN_FILE"

PLUGIN_FILE_BASE=$(basename $PLUGIN_FILE)

ES_VERSION=$(echo $PLUGIN_FILE_BASE | awk -F "_es" {'print $2'} | sed "s/\.zip//")

echo "ES_VERSION: $ES_VERSION"

S3CLI="ci/dummy-s3cmd.sh"
if [[ "$(uname -s)" == *"Linux"* ]]; then
    S3CLI="ci/s3cli"
fi

cat > $CONF_FILE <<- EOM
{
  "bucket_name":            "redirector-readonlyrest.com",
  "credentials_source":     "static",
  "access_key_id":          "${aws_access_key_id}",
  "secret_access_key":      "${aws_secret_access_key}",
  "region":                 "eu-west-1"
}
EOM

# s3cli -c config.json  put <path/to/file> <remote-blob>
$S3CLI  -c $CONF_FILE    put $PLUGIN_FILE    $ES_VERSION/$PLUGIN_FILE_BASE
$S3CLI  -c $CONF_FILE    put $PLUGIN_FILE    $ES_VERSION/$PLUGIN_FILE_BASE.sha1

rm $CONF_FILE

##########################################
# Sample of available ENV vars in Travis #
##########################################

#declare -x SHELL="/bin/bash"
#declare -x SHLVL="2"
#declare -x SSH_CLIENT="104.198.36.26 56467 22"
#declare -x SSH_CONNECTION="104.198.36.26 56467 10.10.2.23 22"
#declare -x SSH_TTY="/dev/pts/1"
#declare -x TERM="dumb"
#declare -x TRAVIS="true"
#declare -x TRAVIS_BRANCH="auto-build"
#declare -x TRAVIS_BUILD_DIR="/home/travis/build/sscarduzio/elasticsearch-readonlyrest-plugin"
#declare -x TRAVIS_BUILD_ID="188755410"
#declare -x TRAVIS_BUILD_NUMBER="229"
#declare -x TRAVIS_COMMIT="7c018b7ad3a6b445f84d66f510edffab236464d9"
#declare -x TRAVIS_COMMIT_RANGE="0c6da9942d7b...7c018b7ad3a6"
#declare -x TRAVIS_EVENT_TYPE="push"
#declare -x TRAVIS_JDK_VERSION="oraclejdk8"
#declare -x TRAVIS_JOB_ID="188755411"
#declare -x TRAVIS_JOB_NUMBER="229.1"
#declare -x TRAVIS_LANGUAGE="java"
#declare -x TRAVIS_OS_NAME="linux"
#declare -x TRAVIS_PULL_REQUEST="false"
#declare -x TRAVIS_PULL_REQUEST_BRANCH=""
#declare -x TRAVIS_PULL_REQUEST_SHA=""
#declare -x TRAVIS_REPO_SLUG="sscarduzio/elasticsearch-readonlyrest-plugin"
#declare -x TRAVIS_SECURE_ENV_VARS="true"
#declare -x TRAVIS_STACK_FEATURES="basic chromium firefox google-chrome jdk memcached mongodb mysql phantomjs postgresql rabbitmq redis sphinxsearch sqlite xserver"
#declare -x TRAVIS_STACK_JOB_BOARD_REGISTER="/.job-board-register.yml"
#declare -x TRAVIS_STACK_LANGUAGES="clojure groovy java pure_java scala"
#declare -x TRAVIS_STACK_NAME="jvm"
#declare -x TRAVIS_STACK_NODE_ATTRIBUTES="/.node-attributes.yml"
#declare -x TRAVIS_STACK_TIMESTAMP="2016-12-02 04:25:24 UTC"
#declare -x TRAVIS_SUDO="true"
#declare -x TRAVIS_TAG=""
#declare -x TRAVIS_TEST_RESULT="0"