#!/bin/bash

set -xe

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION"

export TRAVIS_BRANCH=$(git symbolic-ref --short -q HEAD)

if [ "$BUILD_SOURCEBRANCHNAME" ]
then
 export TRAVIS=true
 export TRAVIS_BRANCH=$BUILD_SOURCEBRANCHNAME
fi
echo ">> FOUND BUILD PARAMETERS: task? $ROR_TASK; is CI? $TRAVIS; branch? $TRAVIS_BRANCH"


# Log file friendly Gradle output
export TERM=dumb

# Adaptation for Azure
( [ ! -z $BUILD_BUILDNUMBER ] || [ "$TRAVIS" ] ) && TRAVIS="true"

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "license" ]]; then
    echo  ">>> Check all license headers are in place"
    ./gradlew license
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "cve_check" ]]; then
    echo ">>> Running CVE checks.."
    ./gradlew dependencyCheckAnalyze
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "core_tests" ]]; then
    echo ">>> Running unit tests.."
    ./gradlew --stacktrace core:test
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_proxy" ]]; then
    echo ">>> proxy => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=proxy' '-Pmode=proxy' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es85x" ]]; then
    echo ">>> es85x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es85x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es84x" ]]; then
    echo ">>> es84x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es84x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es83x" ]]; then
    echo ">>> es83x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es83x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es82x" ]]; then
    echo ">>> es82x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es82x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es81x" ]]; then
    echo ">>> es81x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es81x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es80x" ]]; then
    echo ">>> es80x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es80x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es716x" ]]; then
    echo ">>> es716x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es716x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es714x" ]]; then
    echo ">>> es714x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es714x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es711x" ]]; then
    echo ">>> es711x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es711x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es710x" ]]; then
    echo ">>> es710x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es710x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es79x" ]]; then
    echo ">>> es79x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es79x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es78x" ]]; then
    echo ">>> es78x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es78x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es77x" ]]; then
    echo ">>> es77x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es77x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es74x" ]]; then
    echo ">>> es74x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es74x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es73x" ]]; then
    echo ">>> es73x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es73x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es72x" ]]; then
    echo ">>> es72x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es72x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es70x" ]]; then
    echo ">>> es70x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es70x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es67x" ]]; then
    echo ">>> es67x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es67x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es66x" ]]; then
    echo ">>> es66x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es66x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es65x" ]]; then
    echo ">>> es65x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es65x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es63x" ]]; then
    echo ">>> es63x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es63x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es62x" ]]; then
    echo ">>> es62x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es62x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es61x" ]]; then
    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es61x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es60x" ]]; then
    echo ">>> es60x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es60x' '-Pmode=plugin' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
    echo ">>> won't try to create builds because this is a PR"
    exit 0
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es8xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es85
    ./gradlew --stacktrace es85x:ror '-PesVersion=8.6.0'
    ./gradlew --stacktrace es85x:ror '-PesVersion=8.5.3'
    ./gradlew --stacktrace es85x:ror '-PesVersion=8.5.2'
    ./gradlew --stacktrace es85x:ror '-PesVersion=8.5.1'
    ./gradlew --stacktrace es85x:ror '-PesVersion=8.5.0'

    #es84
    ./gradlew --stacktrace es84x:ror '-PesVersion=8.4.3'
    ./gradlew --stacktrace es84x:ror '-PesVersion=8.4.2'
    ./gradlew --stacktrace es84x:ror '-PesVersion=8.4.1'
    ./gradlew --stacktrace es84x:ror '-PesVersion=8.4.0'

    #es83
    ./gradlew --stacktrace es83x:ror '-PesVersion=8.3.3'
    ./gradlew --stacktrace es83x:ror '-PesVersion=8.3.2'
    ./gradlew --stacktrace es83x:ror '-PesVersion=8.3.1'
    ./gradlew --stacktrace es83x:ror '-PesVersion=8.3.0'

    #es82
    ./gradlew --stacktrace es82x:ror '-PesVersion=8.2.3'
    ./gradlew --stacktrace es82x:ror '-PesVersion=8.2.2'
    ./gradlew --stacktrace es82x:ror '-PesVersion=8.2.1'
    ./gradlew --stacktrace es82x:ror '-PesVersion=8.2.0'

    #es81
    ./gradlew --stacktrace es81x:ror '-PesVersion=8.1.3'
    ./gradlew --stacktrace es81x:ror '-PesVersion=8.1.2'
    ./gradlew --stacktrace es81x:ror '-PesVersion=8.1.1'
    ./gradlew --stacktrace es81x:ror '-PesVersion=8.1.0'

    #es80x
    ./gradlew --stacktrace es80x:ror '-PesVersion=8.0.1'
    ./gradlew --stacktrace es80x:ror '-PesVersion=8.0.0'

fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es7xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es716x
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.8'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.7'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.6'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.5'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.4'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.3'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.2'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.1'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.17.0'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.16.3'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.16.2'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.16.1'
    ./gradlew --stacktrace es716x:ror '-PesVersion=7.16.0'

    #es714x
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.15.2'
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.15.1'
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.15.0'
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.14.2'
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.14.1'
    ./gradlew --stacktrace es714x:ror '-PesVersion=7.14.0'

    #es711x
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.13.4'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.13.3'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.13.2'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.13.1'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.13.0'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.12.1'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.12.0'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.11.2'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.11.1'
    ./gradlew --stacktrace es711x:ror '-PesVersion=7.11.0'

    #es710x
    ./gradlew --stacktrace es710x:ror '-PesVersion=7.10.2'
    ./gradlew --stacktrace es710x:ror '-PesVersion=7.10.1'
    ./gradlew --stacktrace es710x:ror '-PesVersion=7.10.0'

    #es79x
    ./gradlew --stacktrace es79x:ror '-PesVersion=7.9.3'
    ./gradlew --stacktrace es79x:ror '-PesVersion=7.9.2'
    ./gradlew --stacktrace es79x:ror '-PesVersion=7.9.1'
    ./gradlew --stacktrace es79x:ror '-PesVersion=7.9.0'

    #es78x
    ./gradlew --stacktrace es78x:ror '-PesVersion=7.8.1'
    ./gradlew --stacktrace es78x:ror '-PesVersion=7.8.0'

    #es77x
    ./gradlew --stacktrace es77x:ror '-PesVersion=7.7.1'
    ./gradlew --stacktrace es77x:ror '-PesVersion=7.7.0'

    #es74x
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.6.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.5.0'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.2'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.1'
    ./gradlew --stacktrace es74x:ror '-PesVersion=7.4.0'

    #es73x
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.2'
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.1'
    ./gradlew --stacktrace es73x:ror '-PesVersion=7.3.0'

    #es72x
    ./gradlew --stacktrace es72x:ror '-PesVersion=7.2.1'
    ./gradlew --stacktrace es72x:ror '-PesVersion=7.2.0'

    #es70x
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.1.1'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.1.0'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.0.1'
    ./gradlew --stacktrace es70x:ror '-PesVersion=7.0.0'

fi


if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es6xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    # es67x
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.23'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.22'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.21'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.20'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.19'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.18'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.17'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.16'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.15'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.14'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.13'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.12'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.11'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.10'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.9'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.8'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.7'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.6'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.5'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.4'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.3'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.2'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.1'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.8.0'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.7.2'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.7.1'
    ./gradlew --stacktrace es67x:ror '-PesVersion=6.7.0'

    # es66x
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.2'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.1'
    ./gradlew --stacktrace es66x:ror '-PesVersion=6.6.0'

    # es65x
    ./gradlew --stacktrace es65x:ror '-PesVersion=6.5.4'
    ./gradlew --stacktrace es65x:ror '-PesVersion=6.5.3'
    ./gradlew --stacktrace es65x:ror '-PesVersion=6.5.2'
    ./gradlew --stacktrace es65x:ror '-PesVersion=6.5.1'
    ./gradlew --stacktrace es65x:ror '-PesVersion=6.5.0'

    # es63x
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.3'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.2'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.4.0'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.2'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.1'
    ./gradlew --stacktrace es63x:ror '-PesVersion=6.3.0'

   # es62x
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.4'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.3'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.2'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.1'
    ./gradlew --stacktrace es62:ror '-PesVersion=6.2.0'

   # es61x
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.4'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.3'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.2'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.1'
    ./gradlew --stacktrace es61x:ror '-PesVersion=6.1.0'

    # es60x
    ./gradlew --stacktrace es60x:ror '-PesVersion=6.0.1'
    ./gradlew --stacktrace es60x:ror '-PesVersion=6.0.0'

fi

if [[ $ROR_TASK == "publish_artifacts" ]] && [[ $TRAVIS_BRANCH == "master" ]] ; then

    # .travis/secret.pgp is downloaded via Azure secret files, see azure-pipelines.yml

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
