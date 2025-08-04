#!/bin/bash -ex

source "$(dirname "$0")/ci-lib.sh"

trap 'echo "Termination signal received. Exiting..."; exit 1' SIGTERM SIGINT

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

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "license_check" ]]; then
  echo ">>> Check all license headers are in place"
  ./gradlew --no-daemon license
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "cve_check" ]]; then
  echo ">>> Running CVE checks.."
  ./gradlew --no-daemon dependencyCheckAnalyze
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "compile_codebase_check" ]]; then
  echo ">>> Running compile codebase.."
  ./gradlew --no-daemon classes
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "audit_build_check" ]]; then
  echo ">>> Running audit module cross build.."
  ./gradlew --no-daemon --stacktrace audit:crossBuildAssemble
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "core_tests" ]]; then
  echo ">>> Running unit tests.."
  ./gradlew --no-daemon --stacktrace core:test
fi

run_integration_tests() {
  if [ "$#" -ne 1 ]; then
    echo "What ES module should I run integration tests for?"
    return 1
  fi

  ES_MODULE=$1

  echo ">>> $ES_MODULE => Running testcontainers.."
  ./gradlew --no-daemon ror-tools:test integration-tests:test "-PesModule=$ES_MODULE" || (find . | grep hs_err | xargs cat && exit 1)
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es91x" ]]; then
  run_integration_tests "es91x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es90x" ]]; then
  run_integration_tests "es90x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es818x" ]]; then
  run_integration_tests "es818x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es816x" ]]; then
  run_integration_tests "es816x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es815x" ]]; then
  run_integration_tests "es815x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es814x" ]]; then
  run_integration_tests "es814x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es813x" ]]; then
  run_integration_tests "es813x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es812x" ]]; then
  run_integration_tests "es812x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es811x" ]]; then
  run_integration_tests "es811x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es810x" ]]; then
  run_integration_tests "es810x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es89x" ]]; then
  run_integration_tests "es89x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es88x" ]]; then
  run_integration_tests "es88x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es87x" ]]; then
  run_integration_tests "es87x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es85x" ]]; then
  run_integration_tests "es85x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es84x" ]]; then
  run_integration_tests "es84x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es83x" ]]; then
  run_integration_tests "es83x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es82x" ]]; then
  run_integration_tests "es82x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es81x" ]]; then
  run_integration_tests "es81x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es80x" ]]; then
  run_integration_tests "es80x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es717x" ]]; then
  run_integration_tests "es717x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es716x" ]]; then
  run_integration_tests "es716x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es714x" ]]; then
  run_integration_tests "es714x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es711x" ]]; then
  run_integration_tests "es711x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es710x" ]]; then
  run_integration_tests "es710x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es79x" ]]; then
  run_integration_tests "es79x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es78x" ]]; then
  run_integration_tests "es78x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es77x" ]]; then
  run_integration_tests "es77x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es74x" ]]; then
  run_integration_tests "es74x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es73x" ]]; then
  run_integration_tests "es73x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es72x" ]]; then
  run_integration_tests "es72x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es70x" ]]; then
  run_integration_tests "es70x"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "integration_es67x" ]]; then
  run_integration_tests "es67x"
fi

if [[ $TRAVIS_PULL_REQUEST == "true" ]] && [[ $TRAVIS_BRANCH != "master" ]]; then
  echo ">>> won't try to create builds because this is a PR"
  exit 0
fi

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I build plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1

  while IFS= read -r version || [[ -n "$version" ]]; do
    time build_ror_plugin "$version"
  done <"$ROR_VERSIONS_FILE"
}

build_ror_plugin() {
  if [ "$#" -ne 1 ]; then
    echo "What ES version should I build plugin for?"
    return 1
  fi

  local ROR_VERSION=$1

  echo ""
  echo "Building ROR for ES $ROR_VERSION:"
  ./gradlew buildRorPlugin "-PesVersion=$ROR_VERSION" </dev/null
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "build_es9xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "build_es8xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "build_es7xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "build_es6xx" ]]; then
  build_ror_plugins "ci/supported-es-versions/es6x.txt"
fi

upload_pre_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I upload pre-plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1
  local ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  while IFS= read -r version; do
    time upload_pre_ror_plugin "$ROR_VERSION" "$version"
  done <"$ROR_VERSIONS_FILE"
}

upload_pre_ror_plugin() {
  if [ "$#" -ne 2 ]; then
    echo "What ES and ROR version should I upload pre-plugin for?"
    return 1
  fi

  local ROR_VERSION=$1
  local ES_VERSION=$2
  local TAG="v${ROR_VERSION}_es${ES_VERSION}"

  echo ""
  echo "Uploading pre-ROR $ROR_VERSION for ES $ES_VERSION:"

  ./gradlew publishRorPlugin "-PesVersion=$ES_VERSION" </dev/null
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "upload_pre_es9xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "upload_pre_es8xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "upload_pre_es7xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "upload_pre_es6xx" ]]; then
  upload_pre_ror_plugins "ci/supported-es-versions/es6x.txt"
fi

release_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES versions should I release plugins for?"
    return 1
  fi

  local ROR_VERSIONS_FILE=$1
  local ROR_VERSION=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  while IFS= read -r version || [[ -n "$version" ]]; do
    time release_ror_plugin "$ROR_VERSION" "$version"
  done <"$ROR_VERSIONS_FILE"
}

release_ror_plugin() {
  if [ "$#" -ne 2 ]; then
    echo "What ES and ROR version should I release plugin for?"
    return 1
  fi

  local ROR_VERSION=$1
  local ES_VERSION=$2

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ES version format. Expected format: X.Y.Z"
    return 2
  fi

  if ! $DOCKER info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  local TAG="v${ROR_VERSION}_es${ES_VERSION}"

  echo ""
  echo "Releasing ROR $ROR_VERSION for ES $ES_VERSION:"

  if checkTagNotExist "$TAG"; then

    if ! ./gradlew publishRorPlugin "-PesVersion=$ES_VERSION" </dev/null; then
      echo "Failed to publish plugin to S3"
      return 3
    fi

    if docker manifest inspect "docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}" >/dev/null 2>&1; then
      if ! ./gradlew publishEsRorDockerImage "-PesVersion=$ES_VERSION" </dev/null; then
        echo "Failed to publish plugin Docker image"
        return 4
      fi
    else
      echo "WARN: Skipping building and publishing Elasticsearch image with ROR installed because there was no Elasticsearch image for version: $ES_VERSION found in the docker registry"
    fi

    tag "$TAG"
    $DOCKER system prune -fa
  fi
}

public_ror_prebuild_plugin() {
  if [ "$#" -ne 1 ]; then
    echo "What ES version should I release plugin for?"
    return 1
  fi

  local ES_VERSION=$1

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ES version format. Expected format: X.Y.Z"
    return 2
  fi

  if ! $DOCKER info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  echo ""
  echo "PUBLISHING ROR PRE-BUILD for ES $ES_VERSION:"

  if ! ./gradlew publishEsRorPreBuildDockerImage "-PesVersion=$ES_VERSION" </dev/null; then
    echo "Failed to publish plugin prebuild Docker image"
    return 4
  fi

  $DOCKER system prune -fa
}

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "release_es9xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es9x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "release_es8xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es8x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "release_es7xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es7x.txt"
fi

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "release_es6xx" ]]; then
  release_ror_plugins "ci/supported-es-versions/es6x.txt"
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

if [[ -z $TRAVIS ]] || [[ $ROR_TASK == "publish_pre_builds_docker_images" ]]; then

  IFS=', ' read -r -a VERSIONS <<< "$BUILD_ROR_ES_VERSIONS"
  for VERSION in "${VERSIONS[@]}"; do
    if [ -n "$VERSION" ]; then
      public_ror_prebuild_plugin "$VERSION"
    fi
  done

fi
