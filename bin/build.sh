#!/bin/sh

# Check all license headers are in place
./gradlew license

# Log file friendly Gradle output
export TERM=dumb

for esProject in `ls | grep 'es\d\dx'`; do
    echo "Running integration tests for $esProject ..."
    ./gradlew integration-tests:test '-PesModule='$esProject --debug
done

./gradlew --stacktrace --debug test ror &&
# additional build of ES module for specified ES version
# ./gradlew --stacktrace es53x:ror '-PesVersion=5.3.0'
#./gradlew --stacktrace es53x:ror '-PesVersion=5.3.1'
#./gradlew --stacktrace es53x:ror '-PesVersion=5.3.2'
./gradlew --stacktrace es53x:ror '-PesVersion=5.4.0'
./gradlew --stacktrace es53x:ror '-PesVersion=5.4.1'
./gradlew --stacktrace es53x:ror '-PesVersion=5.4.2'

./gradlew --stacktrace es52x:ror '-PesVersion=5.2.2'
./gradlew --stacktrace es52x:ror '-PesVersion=5.2.1'
./gradlew --stacktrace es52x:ror '-PesVersion=5.2.0'
