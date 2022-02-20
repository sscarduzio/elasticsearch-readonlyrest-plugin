#!/bin/bash

set -xe

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

export GRADLE_OPTS="--add-modules java.xml.bind '-Dorg.gradle.jvmargs=--add-modules java.xml.bind'"
./gradlew --stacktrace --info clean es80x:publish