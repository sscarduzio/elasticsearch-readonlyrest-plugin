#!/bin/bash

set -xe

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=9.3.6
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.19.17
