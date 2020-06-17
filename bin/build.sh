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

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_proxy" ]]; then
    echo ">>> proxy => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es74x' '-Pmode=proxy' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es77x" ]]; then
    echo ">>> es77x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es77x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es74x" ]]; then
    echo ">>> es74x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es74x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es73x" ]]; then
    echo ">>> es73x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es73x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es72x" ]]; then
    echo ">>> es72x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es72x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es70x" ]]; then
    echo ">>> es70x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es70x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es66x" ]]; then
    echo ">>> es66x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es66x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es63x" ]]; then
    echo ">>> es63x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es63x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es62x" ]]; then
    echo ">>> es62x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es62x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es61x" ]]; then
    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es61x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es60x" ]]; then
    echo ">>> es60x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es60x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "integration_es55x" ]]; then
    echo ">>> es55x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es55x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
    echo ">>> won't try to create builds because this is a PR"
    exit 0
fi

if [[ $TRAVIS != "true" ]] ||  [[ $ROR_TASK == "package_es7xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es77
    ./gradlew --stacktrace es77x:ror '-PesVersion=7.7.0'
    ./gradlew --stacktrace es77x:ror '-PesVersion=7.7.1'

    #es74
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.2'

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
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.7'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.8'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.9'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.8.10'

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

fi

if [[ $TRAVIS_PULL_REQUEST = "false" ]] && [[ $ROR_TASK == "publish_artifacts" ]] && [[ $TRAVIS_BRANCH == "master" ]]; then

    openssl aes-256-cbc -K $encrypted_31be120daa3b_key -iv $encrypted_31be120daa3b_iv -in .travis/secret.pgp.enc -out .travis/secret.pgp -d

    CURRENT_PLUGIN_VER=$(awk -F= '$1=="pluginVersion" {print $2}' gradle.properties)
    PUBLISHED_PLUGIN_VER=$(awk -F= '$1=="publishedPluginVersion" {print $2}' gradle.properties)

    # Check if this tag already exists, so we don't overwrite builds
    if git tag --list | egrep -e "^v${PUBLISHED_PLUGIN_VER}_es" > /dev/null; then
        echo "Skipping publishing audit module artifacts because GIT tag $PUBLISHED_PLUGIN_VER already exists, exiting."
    else 
      if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
        echo ">>> Publishing audit module artifacts to sonatype repo"
        ./gradlew audit:publishToSonatype
        ./gradlew audit:closeAndReleaseRepository
      else
        echo ">>> Skipping publishing audit module artifacts"
      fi
    fi
    
    
fi
