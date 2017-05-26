#!/bin/sh
./gradlew --stacktrace test ror &&
# additional build of ES module for specified ES version
./gradlew --stacktrace es53x:ror '-PesVersion=5.3.0'