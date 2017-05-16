#!/bin/sh
./gradlew updateSHAs check assemble
# additional build of ES module for specified ES version
./gradlew es53x:assemble '-PesVersion=5.3.0'
# -x test
