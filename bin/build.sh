#!/bin/bash

set -xe

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION"

# Log file friendly Gradle output
export TERM=dumb


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "license" ]]; then

    echo  ">>> Check all license headers are in place"
    ./gradlew license

fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "unit" ]]; then

    echo ">>> Running unit tests.."
    ./gradlew --stacktrace test ror

fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es63x" ]]; then

    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es63x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es61x" ]]; then

    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es61x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es60x" ]]; then

    echo ">>> es60x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es60x' || ( find . |grep hs_err |xargs cat && exit 1 )

fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es53x" ]]; then

    echo ">>> es53x => Running testcontainers.."
    ./gradlew  integration-tests:test '-PesModule=es53x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es52x" ]]; then

    echo ">>> es52x => Running testcontainers.."
    ./gradlew  integration-tests:test '-PesModule=es52x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es51x" ]]; then

    echo ">>> es51x => Running testcontainers.."
    ./gradlew  integration-tests:test '-PesModule=es51x' || ( find . |grep hs_err |xargs cat && exit 1 )

fi


if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
    echo ">>> won't try to create builds because this is a PR"
    exit 0
fi


if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    # es64
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.2'

    # es63
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.2'
 
   # es61
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.0'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.1'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.2'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.3'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.4'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.2.0'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.2.1'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.2.2'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.2.3'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.2.4'


    # es60
    ./gradlew --stacktrace es60x:ror '-PesVersion=6.0.0'
    #./gradlew --stacktrace es60x:ror '-PesVersion=6.0.1'


    # es53
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.0'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.1'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.2'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.3'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.4'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.5'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.6'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.7'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.8'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.9'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.6.10'
    #./gradlew --stacktrace es53x:ror '-PesVersion=5.6.11'

    ./gradlew --stacktrace es53x:ror '-PesVersion=5.5.0'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.5.1'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.5.2'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.5.3'

    ./gradlew --stacktrace es53x:ror '-PesVersion=5.4.0'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.4.1'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.4.2'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.4.3'

    ./gradlew --stacktrace es53x:ror '-PesVersion=5.3.0'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.3.1'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.3.2'
    ./gradlew --stacktrace es53x:ror '-PesVersion=5.3.3'


    # es52
    ./gradlew --stacktrace es52x:ror '-PesVersion=5.2.0'
    ./gradlew --stacktrace es52x:ror '-PesVersion=5.2.1'
    #./gradlew --stacktrace es52x:ror '-PesVersion=5.2.2'


    # es51
    ./gradlew --stacktrace es51x:ror '-PesVersion=5.1.1'
    #./gradlew --stacktrace es51x:ror '-PesVersion=5.1.2'

    # es23x
    ./gradlew --stacktrace es23x:ror '-PesVersion=2.4.0'
    ./gradlew --stacktrace es23x:ror '-PesVersion=2.4.1'
    #./gradlew --stacktrace es23x:ror '-PesVersion=2.4.2'
    #./gradlew --stacktrace es23x:ror '-PesVersion=2.4.3'
    ./gradlew --stacktrace es23x:ror '-PesVersion=2.4.4'
    ./gradlew --stacktrace es23x:ror '-PesVersion=2.4.5'
    #./gradlew --stacktrace es23x:ror '-PesVersion=2.4.6'

fi
