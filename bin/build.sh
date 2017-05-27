#!/bin/sh

# Log file friendly Gradle output
export TERM=dumb

./gradlew --stacktrace --debug test ror &&
# additional build of ES module for specified ES version
./gradlew --stacktrace --debug es53x:ror '-PesVersion=5.3.0'