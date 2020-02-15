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

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es74x_scala" ]]; then
    echo ">>> es74x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es74x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es73x_scala" ]]; then
    echo ">>> es73x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es73x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es72x_scala" ]]; then
    echo ">>> es72x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es72x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es70x_scala" ]]; then
    echo ">>> es70x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es70x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es66x_scala" ]]; then
    echo ">>> es66x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es66x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es63x_scala" ]]; then
    echo ">>> es63x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es63x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es62x_scala" ]]; then
    echo ">>> es62x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es63x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es61x_scala" ]]; then
    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es61x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es60x_scala" ]]; then
    echo ">>> es60x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es60x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es55x_scala" ]]; then
    echo ">>> es55x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es55x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es53x_scala" ]]; then
    echo ">>> es53x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es53x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es52x_scala" ]]; then
    echo ">>> es52x => Running testcontainers.."
    ./gradlew integration-tests-scala:test '-PesModule=es52x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es51x_scala" ]]; then
    echo ">>> es51x => Running testcontainers.."1
    ./gradlew integration-tests-scala:test '-PesModule=es51x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
    echo ">>> won't try to create builds because this is a PR"
    exit 0
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package_es7xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es74
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.0'

    #es73
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.0'
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.1'
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.2'

    #es72
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.2.0'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.2.1'

    #es70
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.0.0'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.0.1'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.1.0'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.1.1'

fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package_es6xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package_es6xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    # es66
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.0'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.1'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.2'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.7.0'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.7.1'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.7.2'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.0'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.1'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.2'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.3'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.4'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.5'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.6'

    # es65
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.5.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.5.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.5.2'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.5.3'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.5.4'

    # es64
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.2'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.3'

    # es63
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.2'

   # es62
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.0'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.1'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.2'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.3'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.4'

   # es61
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.0'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.1'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.2'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.3'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.4'

    # es60
    ./gradlew --stacktrace es60x:ror '-PesVersion=6.0.0'
    #./gradlew --stacktrace es60x:ror '-PesVersion=6.0.1'

fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package_es5xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    # es55
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.0'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.1'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.2'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.3'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.4'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.5'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.6'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.7'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.8'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.9'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.10'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.11'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.12'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.13'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.14'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.15'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.6.16'

    ./gradlew --stacktrace es55x:ror '-PesVersion=5.5.0'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.5.1'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.5.2'
    ./gradlew --stacktrace es55x:ror '-PesVersion=5.5.3'

    # es53
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

fi

if [[ $TRAVIS_PULL_REQUEST = "false" ]] && [[ $ROR_TASK == "publish_artifacts" ]] && [[ $TRAVIS_BRANCH == "master" ]]; then

    openssl aes-256-cbc -K $encrypted_31be120daa3b_key -iv $encrypted_31be120daa3b_iv -in .travis/secret.pgp.enc -out .travis/secret.pgp -d

    CURRENT_PLUGIN_VER=$(awk -F= '$1=="pluginVersion" {print $2}' gradle.properties)
    PUBLISHED_PLUGIN_VER=$(awk -F= '$1=="publishedPluginVersion" {print $2}' gradle.properties)

    if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
        echo ">>> Publishing audit module artifacts to sonatype repo"
        ./gradlew audit:publishToSonatype
        ./gradlew audit:closeAndReleaseRepository
    else
        echo ">>> Skipping publishing audit module artifacts"
    fi
fi