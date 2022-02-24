#!/bin/bash -e

CI_DIR=$(dirname "$0")

# TAGGING
function tag {
    GIT_TAG="$1"

    # Check if this tag already exists, so we don't overwrite builds
    if git tag --list | egrep -e "^${GIT_TAG}$" > /dev/null; then
        echo "Git tag $GIT_TAG already exists, exiting."
        return 1
    fi

    echo "Tagging as $GIT_TAG"
    if [[ "$(uname -s)" == *"Linux"* ]]; then
        git remote set-url origin git@github.com:sscarduzio/elasticsearch-readonlyrest-plugin.git
        git config --global push.default matching
        git config --global user.email "builds@travis-ci.com"
        git config --global user.name "Travis CI"
        git tag $GIT_TAG -a -m "Generated tag from TravisCI build $TRAVIS_BUILD_NUMBER"
        git push origin $GIT_TAG
    fi
    return 0
}

# UPLOADING
# i.e. upload file.zip build/v123/file.zip
function upload {
    CONF_FILE="conf.json"
    BUCKET="readonlyrest-data"

    uname -m | grep x86     > /dev/null && echo "Discovered x86 processor on Linux" && export S3CLI="$CI_DIR/s3cli"
    uname -m | grep aarch64 > /dev/null && echo "Discovered ARM processor on Linux" && export S3CLI="$CI_DIR/s3cli-aarch64"
    uname -m | grep arm64   > /dev/null && echo "Discovered ARM processor on Mac"   && export S3CLI="$CI_DIR/s3cli-arm64"
    echo ">>> Using s3 client binary $S3CLI"

cat > $CONF_FILE <<- EOM
{
  "bucket_name":            "${BUCKET}",
  "credentials_source":     "static",
  "access_key_id":          "${aws_access_key_id}",
  "secret_access_key":      "${aws_secret_access_key}",
  "region":                 "eu-west-1"
}
EOM

    LOCAL_FILE="$1"
    S3_PATH="$2"

    # s3cli -c config.json  put <path/to/file> <remote-blob>
    RES=$($S3CLI -c $CONF_FILE put "$LOCAL_FILE"  "$S3_PATH" || echo fail)

    if [[ $RES != "fail" ]]; then
      echo ">> uploaded $S3_PATH"
    else
      echo ">> could not upload to $S3_PATH"
    fi
    rm $CONF_FILE
}