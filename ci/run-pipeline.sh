#!/bin/bash -ex

source "$(dirname "$0")/ci-lib.sh"
source "$(dirname "$0")/publish-ror-plugins.sh"

trap 'echo "Termination signal received. Exiting..."; exit 1' SIGTERM SIGINT

echo ">>> ($0) RUNNING CONTINUOUS INTEGRATION; task? $ROR_TASK"

# Log file friendly Gradle output
export TERM=dumb

if [[ $ROR_TASK == "license_check" ]]; then
  echo ">>> Check all license headers are in place"
  ./gradlew --no-daemon license
fi

if [[ $ROR_TASK == "cve_check" ]]; then
  echo ">>> Running CVE checks.."
  ./gradlew --no-daemon dependencyCheckAnalyze
fi

if [[ $ROR_TASK == "compile_codebase_check" ]]; then
  echo ">>> Running compile codebase.."
  ./gradlew --no-daemon classes
fi

if [[ $ROR_TASK == "audit_build_check" ]]; then
  echo ">>> Running audit module cross build.."
  ./gradlew --no-daemon --stacktrace audit:crossBuildAssemble
fi

if [[ $ROR_TASK == "core_tests" ]]; then
  echo ">>> Running unit tests.."
  ./gradlew --no-daemon --stacktrace core:test audit:test build-base:test
fi

run_integration_tests() {
  if [ "$#" -ne 1 ]; then
    echo "What ES module should I run integration tests for?"
    return 1
  fi

  ES_MODULE=$1
  local gradleArgs=("--no-daemon" "ror-tools:test" "integration-tests:test" "-PesModule=$ES_MODULE")
  [ -n "$ES_VERSION" ] && gradleArgs+=("-PesVersion=$ES_VERSION")

  echo ">>> $ES_MODULE => Running integration tests.."
  ./gradlew "${gradleArgs[@]}" || (find . | grep hs_err | xargs cat && exit 1)
}

if [[ $ROR_TASK == "integration_es94x" ]]; then
  run_integration_tests "es94x"
fi

if [[ $ROR_TASK == "integration_es92x" ]]; then
  run_integration_tests "es92x"
fi

if [[ $ROR_TASK == "integration_es91x" ]]; then
  run_integration_tests "es91x"
fi

if [[ $ROR_TASK == "integration_es90x" ]]; then
  run_integration_tests "es90x"
fi

if [[ $ROR_TASK == "integration_es818x" ]]; then
  run_integration_tests "es818x"
fi

if [[ $ROR_TASK == "integration_es816x" ]]; then
  run_integration_tests "es816x"
fi

if [[ $ROR_TASK == "integration_es815x" ]]; then
  run_integration_tests "es815x"
fi

if [[ $ROR_TASK == "integration_es814x" ]]; then
  run_integration_tests "es814x"
fi

if [[ $ROR_TASK == "integration_es813x" ]]; then
  run_integration_tests "es813x"
fi

if [[ $ROR_TASK == "integration_es812x" ]]; then
  run_integration_tests "es812x"
fi

if [[ $ROR_TASK == "integration_es811x" ]]; then
  run_integration_tests "es811x"
fi

if [[ $ROR_TASK == "integration_es810x" ]]; then
  run_integration_tests "es810x"
fi

if [[ $ROR_TASK == "integration_es89x" ]]; then
  run_integration_tests "es89x"
fi

if [[ $ROR_TASK == "integration_es88x" ]]; then
  run_integration_tests "es88x"
fi

if [[ $ROR_TASK == "integration_es87x" ]]; then
  run_integration_tests "es87x"
fi

if [[ $ROR_TASK == "integration_es85x" ]]; then
  run_integration_tests "es85x"
fi

if [[ $ROR_TASK == "integration_es84x" ]]; then
  run_integration_tests "es84x"
fi

if [[ $ROR_TASK == "integration_es83x" ]]; then
  run_integration_tests "es83x"
fi

if [[ $ROR_TASK == "integration_es82x" ]]; then
  run_integration_tests "es82x"
fi

if [[ $ROR_TASK == "integration_es81x" ]]; then
  run_integration_tests "es81x"
fi

if [[ $ROR_TASK == "integration_es80x" ]]; then
  run_integration_tests "es80x"
fi

if [[ $ROR_TASK == "integration_es717x" ]]; then
  run_integration_tests "es717x"
fi

if [[ $ROR_TASK == "integration_es716x" ]]; then
  run_integration_tests "es716x"
fi

if [[ $ROR_TASK == "integration_es714x" ]]; then
  run_integration_tests "es714x"
fi

if [[ $ROR_TASK == "integration_es711x" ]]; then
  run_integration_tests "es711x"
fi

if [[ $ROR_TASK == "integration_es710x" ]]; then
  run_integration_tests "es710x"
fi

if [[ $ROR_TASK == "integration_es79x" ]]; then
  run_integration_tests "es79x"
fi

if [[ $ROR_TASK == "integration_es78x" ]]; then
  run_integration_tests "es78x"
fi

if [[ $ROR_TASK == "integration_es77x" ]]; then
  run_integration_tests "es77x"
fi

if [[ $ROR_TASK == "integration_es74x" ]]; then
  run_integration_tests "es74x"
fi

if [[ $ROR_TASK == "integration_es73x" ]]; then
  run_integration_tests "es73x"
fi

if [[ $ROR_TASK == "integration_es72x" ]]; then
  run_integration_tests "es72x"
fi

if [[ $ROR_TASK == "integration_es70x" ]]; then
  run_integration_tests "es70x"
fi

if [[ $ROR_TASK == "integration_es67x" ]]; then
  run_integration_tests "es67x"
fi

build_ror_plugins() {
  if [ "$#" -ne 1 ]; then
    echo "What ES generation (major: 6|7|8|9) should I verify plugins for?"
    return 1
  fi

  local es_major=$1

  local module
  while IFS= read -r module; do
    [ -z "$module" ] && continue
    if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
      return 1
    fi
  done < <(list_es_modules "$es_major")
}

if [[ $ROR_TASK == "build_es9xx" ]]; then
  build_ror_plugins "9"
fi

if [[ $ROR_TASK == "build_es8xx" ]]; then
  build_ror_plugins "8"
fi

if [[ $ROR_TASK == "build_es7xx" ]]; then
  build_ror_plugins "7"
fi

if [[ $ROR_TASK == "build_es6xx" ]]; then
  build_ror_plugins "6"
fi

if [[ $ROR_TASK == "upload_pre_es9xx" ]]; then
  publish_ror_plugins "9" "upload_pre"
fi

if [[ $ROR_TASK == "upload_pre_es8xx" ]]; then
  publish_ror_plugins "8" "upload_pre"
fi

if [[ $ROR_TASK == "upload_pre_es7xx" ]]; then
  publish_ror_plugins "7" "upload_pre"
fi

if [[ $ROR_TASK == "upload_pre_es6xx" ]]; then
  publish_ror_plugins "6" "upload_pre"
fi

if [[ $ROR_TASK == "release_es9xx" ]]; then
  publish_ror_plugins "9" "release"
fi

if [[ $ROR_TASK == "release_es8xx" ]]; then
  publish_ror_plugins "8" "release"
fi

if [[ $ROR_TASK == "release_es7xx" ]]; then
  publish_ror_plugins "7" "release"
fi

if [[ $ROR_TASK == "release_es6xx" ]]; then
  publish_ror_plugins "6" "release"
fi

check_maven_artifacts_exist() {
  local CURRENT_VERSION="$1"

  local ARTIFACT_URL="https://repo1.maven.org/maven2/tech/beshu/ror/audit_3/$CURRENT_VERSION/"
  echo ">>> Checking if Maven artifacts already exist at: $ARTIFACT_URL"
  
  local MVN_STATUS=$(curl -L --write-out '%{http_code}' --silent --output /dev/null "$ARTIFACT_URL" || echo "000")
  
  if [[ $MVN_STATUS == "404" ]]; then
    echo ">>> Maven artifacts not found"
    return 1
  elif [[ $MVN_STATUS == "200" ]]; then
    echo ">>> Maven artifacts for version $CURRENT_VERSION already exist."
    return 0
  else
    echo ">>> ERROR: Unexpected HTTP status $MVN_STATUS when checking Maven repository"
    echo ">>> Cannot determine if artifacts exist, failing to avoid potential issues"
    exit 1
  fi
}

if [[ $ROR_TASK == "publish_maven_artifacts" ]]; then
  # .travis/secret.pgp is downloaded via Azure secret files, see azure-pipelines.yml
  CURRENT_PLUGIN_VER=$(awk -F= '$1=="pluginVersion" {print $2}' gradle.properties)
  PUBLISHED_PLUGIN_VER=$(awk -F= '$1=="publishedPluginVersion" {print $2}' gradle.properties)

  if [[ $CURRENT_PLUGIN_VER == $PUBLISHED_PLUGIN_VER ]]; then
    if check_maven_artifacts_exist "$CURRENT_PLUGIN_VER"; then
      echo ">>> Skipping publishing audit module artifacts"
    else
      echo ">>> Publishing audit module artifacts to sonatype repo"
      ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
    fi
  else
    echo ">>> Version mismatch: current=$CURRENT_PLUGIN_VER, published=$PUBLISHED_PLUGIN_VER"
    echo ">>> Skipping publishing audit module artifacts."
  fi
fi

if [[ $ROR_TASK == "publish_pre_builds_docker_images" ]]; then

  if [ -z "$(echo "$BUILD_ROR_ES_VERSIONS" | tr -d '[:space:],')" ]; then
    echo "Error: BUILD_ROR_ES_VERSIONS is required"
    exit 1
  fi

  # IMAGE_TAG is optional; its pipeline default is a single space, so normalize whitespace-only to empty.
  IMAGE_TAG="$(echo "${IMAGE_TAG:-}" | tr -d '[:space:]')"

  IFS=', ' read -r -a VERSIONS <<< "$BUILD_ROR_ES_VERSIONS"
  for VERSION in "${VERSIONS[@]}"; do
    if [ -n "$VERSION" ]; then
      publish_ror_prebuild_plugin "$VERSION" "$IMAGE_TAG"
      docker system prune -fa
    fi
  done

fi
