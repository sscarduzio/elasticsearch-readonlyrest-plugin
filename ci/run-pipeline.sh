#!/bin/bash -xe

source "$(dirname "$0")/ci-lib.sh"

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION"

export TRAVIS_BRANCH=$(git symbolic-ref --short -q HEAD)

if [ "$BUILD_SOURCEBRANCHNAME" ]; then
  export TRAVIS=true
  export TRAVIS_BRANCH=$BUILD_SOURCEBRANCHNAME
fi
echo ">> FOUND BUILD PARAMETERS: task? $ROR_TASK; is CI? $TRAVIS; branch? $TRAVIS_BRANCH"

# Log file friendly Gradle output
export TERM=dumb

# Adaptation for Azure
([ ! -z $BUILD_BUILDNUMBER ] || [ "$TRAVIS" ]) && TRAVIS="true"

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "license" ]]; then
  echo ">>> Check all license headers are in place"
  ./gradlew license
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "cve_check" ]]; then
  echo ">>> Running CVE checks.."
  ./gradlew dependencyCheckAnalyze
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "audit_compile" ]]; then
  echo ">>> Running audit module cross build.."
  ./gradlew --stacktrace audit:crossBuildAssemble
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "core_tests" ]]; then
  echo ">>> Running unit tests.."
  ./gradlew --stacktrace core:test
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es811x" ]]; then
  echo ">>> es811x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es811x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es810x" ]]; then
  echo ">>> es810x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es810x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es89x" ]]; then
  echo ">>> es89x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es89x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es88x" ]]; then
  echo ">>> es88x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es88x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es87x" ]]; then
  echo ">>> es87x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es87x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es85x" ]]; then
  echo ">>> es85x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es85x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es84x" ]]; then
  echo ">>> es84x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es84x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es83x" ]]; then
  echo ">>> es83x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es83x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es82x" ]]; then
  echo ">>> es82x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es82x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es81x" ]]; then
  echo ">>> es81x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es81x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es80x" ]]; then
  echo ">>> es80x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es80x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es717x" ]]; then
  echo ">>> es717x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es717x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es716x" ]]; then
  echo ">>> es716x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es716x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es714x" ]]; then
  echo ">>> es714x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es714x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es711x" ]]; then
  echo ">>> es711x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es711x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es710x" ]]; then
  echo ">>> es710x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es710x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es79x" ]]; then
  echo ">>> es79x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es79x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es78x" ]]; then
  echo ">>> es78x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es78x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es77x" ]]; then
  echo ">>> es77x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es77x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es74x" ]]; then
  echo ">>> es74x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es74x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es73x" ]]; then
  echo ">>> es73x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es73x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es72x" ]]; then
  echo ">>> es72x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es72x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es70x" ]]; then
  echo ">>> es70x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es70x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es67x" ]]; then
  echo ">>> es67x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es67x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es66x" ]]; then
  echo ">>> es66x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es66x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es65x" ]]; then
  echo ">>> es65x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es65x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es63x" ]]; then
  echo ">>> es63x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es63x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es62x" ]]; then
  echo ">>> es62x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es62x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es61x" ]]; then
  echo ">>> es61x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es61x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es60x" ]]; then
  echo ">>> es60x => Running testcontainers.."
  ./gradlew integration-tests:test '-PesModule=es60x' || (find . | grep hs_err | xargs cat && exit 1)
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
  echo ">>> won't try to create builds because this is a PR"
  exit 0
fi

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions I should build plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1

  while IFS= read -r version; do
    echo "Building ROR for ES $version:"
    ./gradlew buildRorPlugin "-PesVersion=$version"
  done <"$ROR_VERSIONS_FILE"
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "package_es8xx" ]]; then
  build_ror_plugins "bin/supported-es-versions/es8x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "package_es7xx" ]]; then
  build_ror_plugins "bin/supported-es-versions/es7x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "package_es6xx" ]]; then
  build_ror_plugins "bin/supported-es-versions/es6x.txt"
fi

publish_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions I should build and publish plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1
  local ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  while IFS= read -r version; do
    echo "Building and publishing ROR $ROR_VERSION for ES $version:"

    tag "v$ROR_VERSION"
    ./gradlew publishRorPlugin "-PesVersion=$version"
    ./gradlew publishEsRorDockerImage "-PesVersion=$version"
  done <"$ROR_VERSIONS_FILE"
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "publish_es8xx" ]]; then
  publish_ror_plugins "bin/supported-es-versions/es8x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "publish_es7xx" ]]; then
  publish_ror_plugins "bin/supported-es-versions/es7x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "publish_es6xx" ]]; then
  publish_ror_plugins "bin/supported-es-versions/es6x.txt"
fi

if [[ $ROR_TASK == "publish_maven_artifacts" ]] && [[ $TRAVIS_BRANCH == "master" ]]; then

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
