#!/bin/bash

set -xe

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.2.0
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.1.3
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.1.2
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.1.1
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.1.0
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.0.1
./gradlew --stacktrace --info clean ror-tools:uploadArtifactsFromEsBinaries -PesVersion=8.0.0
