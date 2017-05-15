#!/bin/sh
gradle wrapper
./gradlew --no-daemon updateSHAs check assemble
