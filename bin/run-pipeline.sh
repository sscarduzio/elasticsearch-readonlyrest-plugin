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

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "audit_compile" ]]; then
    echo ">>> Running audit module cross build.."
    ./gradlew --stacktrace audit:crossBuildAssemble
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "core_tests" ]]; then
    echo ">>> Running unit tests.."
    ./gradlew --stacktrace core:test
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es811x" ]]; then
    echo ">>> es811x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es811x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es810x" ]]; then
    echo ">>> es810x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es810x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es89x" ]]; then
    echo ">>> es89x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es89x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es88x" ]]; then
    echo ">>> es88x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es88x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es87x" ]]; then
    echo ">>> es87x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es87x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es85x" ]]; then
    echo ">>> es85x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es85x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es84x" ]]; then
    echo ">>> es84x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es84x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es83x" ]]; then
    echo ">>> es83x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es83x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es82x" ]]; then
    echo ">>> es82x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es82x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es81x" ]]; then
    echo ">>> es81x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es81x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es80x" ]]; then
    echo ">>> es80x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es80x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es717x" ]]; then
    echo ">>> es717x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es717x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es716x" ]]; then
    echo ">>> es716x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es716x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es714x" ]]; then
    echo ">>> es714x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es714x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es711x" ]]; then
    echo ">>> es711x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es711x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es710x" ]]; then
    echo ">>> es710x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es710x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es79x" ]]; then
    echo ">>> es79x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es79x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es78x" ]]; then
    echo ">>> es78x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es78x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es77x" ]]; then
    echo ">>> es77x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es77x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es74x" ]]; then
    echo ">>> es74x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es74x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es73x" ]]; then
    echo ">>> es73x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es73x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es72x" ]]; then
    echo ">>> es72x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es72x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es70x" ]]; then
    echo ">>> es70x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es70x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es67x" ]]; then
    echo ">>> es67x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es67x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es66x" ]]; then
    echo ">>> es66x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es66x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es65x" ]]; then
    echo ">>> es65x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es65x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es63x" ]]; then
    echo ">>> es63x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es63x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es62x" ]]; then
    echo ">>> es62x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es62x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es61x" ]]; then
    echo ">>> es61x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es61x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "integration_es60x" ]]; then
    echo ">>> es60x => Running testcontainers.."
    ./gradlew integration-tests:test '-PesModule=es60x' || ( find . |grep hs_err |xargs cat && exit 1 )
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
    echo ">>> won't try to create builds because this is a PR"
    exit 0
fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es8xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es811x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.11.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.11.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.11.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.11.0'

    #es810x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.10.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.10.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.10.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.10.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.10.0'

    #es89x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.9.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.9.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.9.0'

    #es88x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.8.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.8.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.8.0'

    #es87x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.7.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.7.0'

    #es85x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.6.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.6.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.6.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.5.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.5.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.5.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.5.0'

    #es84x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.4.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.4.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.4.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.4.0'

    #es83x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.3.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.3.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.3.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.3.0'

    #es82x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.2.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.2.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.2.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.2.0'

    #es81x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.1.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.1.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.1.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.1.0'

    #es80x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.0.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=8.0.0'

fi

if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es7xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    #es717x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.16'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.15'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.14'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.13'

    #es716x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.12'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.11'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.10'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.9'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.8'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.7'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.6'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.5'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.17.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.16.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.16.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.16.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.16.0'

    #es714x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.15.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.15.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.15.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.14.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.14.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.14.0'

    #es711x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.13.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.13.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.13.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.13.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.13.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.12.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.12.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.11.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.11.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.11.0'

    #es710x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.10.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.10.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.10.0'

    #es79x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.9.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.9.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.9.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.9.0'

    #es78x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.8.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.8.0'

    #es77x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.7.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.7.0'

    #es74x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.6.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.6.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.6.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.5.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.5.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.5.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.4.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.4.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.4.0'

    #es73x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.3.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.3.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.3.0'

    #es72x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.2.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.2.0'

    #es70x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.1.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.1.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.0.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=7.0.0'

fi


if [[ -z $TRAVIS ]] ||  [[ $ROR_TASK == "package_es6xx" ]]; then

    echo ">>> ($0) additional builds of ES module for specified ES version"

    # es67x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.23'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.22'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.21'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.20'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.19'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.18'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.17'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.16'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.15'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.14'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.13'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.12'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.11'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.10'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.9'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.8'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.7'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.6'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.5'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.8.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.7.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.7.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.7.0'

    # es66x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.6.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.6.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.6.0'

    # es65x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.5.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.5.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.5.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.5.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.5.0'

    # es63x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.4.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.4.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.4.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.4.0'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.3.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.3.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.3.0'

   # es62x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.2.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.2.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.2.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.2.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.2.0'

   # es61x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.1.4'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.1.3'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.1.2'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.1.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.1.0'

    # es60x
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.0.1'
    ./gradlew --stacktrace buildRorPlugin '-PesVersion=6.0.0'

fi

if [[ $ROR_TASK == "publish_artifacts" ]] && [[ $TRAVIS_BRANCH == "master" ]] ; then

    # .travis/secret.pgp is downloaded via Azure secret files, see azure-pipelines.yml

    CURRENT_PLUGIN_VER=$(awk -F= '$1=="pluginVersion" {print $2}' gradle.properties)
    PUBLISHED_PLUGIN_VER=$(awk -F= '$1=="publishedPluginVersion" {print $2}' gradle.properties)

    if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
      echo ">>> Publishing audit module artifacts to sonatype repo"
      ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
    else
      echo ">>> Skipping publishing audit module artifacts"
    fi
fi