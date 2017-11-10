#!/bin/sh

set -xe

echo ">>> ($0) RUNNINIG CONTINUOUS INTEGRATION"

# Log file friendly Gradle output
export TERM=dumb

echo  ">>> Check all license headers are in place"
./gradlew license

echo ">>> Running unit tests.."
./gradlew --stacktrace test ror


echo ">>> es54x => Running testcontainers.."
./gradlew integration-tests:test '-PesModule=es54x' || ( find . |grep hs_err |xargs cat && exit 1 )


echo ">>> es53x => Running testcontainers.."
./gradlew  integration-tests:test '-PesModule=es53x' || ( find . |grep hs_err |xargs cat && exit 1 )


echo ">>> es52x => Running testcontainers.."
./gradlew  integration-tests:test '-PesModule=es52x' || ( find . |grep hs_err |xargs cat && exit 1 )

echo ">>> es51x => Running testcontainers.."
./gradlew  integration-tests:test '-PesModule=es51x' || ( find . |grep hs_err |xargs cat && exit 1 )


echo ">>> ($0) additional build of ES module for specified ES version"

./gradlew --stacktrace es54x:ror '-PesVersion=5.6.0'
./gradlew --stacktrace es54x:ror '-PesVersion=5.6.1'
./gradlew --stacktrace es54x:ror '-PesVersion=5.6.2'

./gradlew --stacktrace es54x:ror '-PesVersion=5.5.0'
./gradlew --stacktrace es54x:ror '-PesVersion=5.5.1'
./gradlew --stacktrace es54x:ror '-PesVersion=5.5.2'
./gradlew --stacktrace es54x:ror '-PesVersion=5.5.3'

./gradlew --stacktrace es54x:ror '-PesVersion=5.4.0'
./gradlew --stacktrace es54x:ror '-PesVersion=5.4.1'
./gradlew --stacktrace es54x:ror '-PesVersion=5.4.2'
./gradlew --stacktrace es54x:ror '-PesVersion=5.4.3'


./gradlew --stacktrace es53x:ror '-PesVersion=5.3.0'
./gradlew --stacktrace es53x:ror '-PesVersion=5.3.1'
./gradlew --stacktrace es53x:ror '-PesVersion=5.3.2'

./gradlew --stacktrace es23x:ror '-PesVersion=2.4.1'
./gradlew --stacktrace es23x:ror '-PesVersion=2.4.4'
./gradlew --stacktrace es23x:ror '-PesVersion=2.4.5'
./gradlew --stacktrace es23x:ror '-PesVersion=2.4.6'

./gradlew --stacktrace es52x:ror '-PesVersion=5.2.2'
./gradlew --stacktrace es52x:ror '-PesVersion=5.2.1'
./gradlew --stacktrace es52x:ror '-PesVersion=5.2.0'

./gradlew --stacktrace es51x:ror '-PesVersion=5.1.1'
