#!/bin/bash

set -xe

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

./gradlew --stacktrace --info clean es80x:publish