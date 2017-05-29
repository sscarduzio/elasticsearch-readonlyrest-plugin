#!/bin/sh

# Log file friendly Gradle output
export TERM=dumb

for esProject in `ls | grep 'es\d\dx'`; do
    echo "Running integration tests for $esProject ..."
    ./gradlew integration-tests:test '-PesModule='$esProject --debug
done

./gradlew --stacktrace --debug test ror &&
# additional build of ES module for specified ES version
./gradlew --stacktrace --debug es53x:ror '-PesVersion=5.3.0'