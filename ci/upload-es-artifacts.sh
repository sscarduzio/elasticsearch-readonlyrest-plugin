#!/bin/bash

set -xe

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=9.4.4
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=9.3.8
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.19.19
