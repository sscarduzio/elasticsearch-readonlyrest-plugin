#!/bin/sh
./gradlew test ror &&
# additional build of ES module for specified ES version
./gradlew es53x:ror '-PesVersion=5.3.0'