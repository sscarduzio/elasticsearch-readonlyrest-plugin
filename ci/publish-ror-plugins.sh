#!/bin/bash
#
# Function library for publishing ROR plugins (build once per ES module, repackage for each supported version).
# Sourced by ci/run-pipeline.sh.

cleanup_docker_and_build() {
  # Exclude the container this script is running inside (prevents self-removal in DinD setups).
  local SELF_ID
  SELF_ID=$(hostname 2>/dev/null || true)
  local containers_to_remove
  if [ -n "$SELF_ID" ]; then
    containers_to_remove=$(docker ps -aq | grep -v "^${SELF_ID}" || true)
  else
    containers_to_remove=$(docker ps -aq || true)
  fi
  [ -n "$containers_to_remove" ] && echo "$containers_to_remove" | xargs docker rm -f || true
  docker builder prune -af || true
  docker system prune -af --volumes || true
  find . -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
}

# Emits one ES module name per line for the given generation (newest module first).
list_es_modules() {
  local es_major=$1
  ./gradlew printEsModules "-PesMajor=$es_major" --quiet </dev/null
}

# Emits two lines: the base ES version on line 1, all supported versions space-separated on line 2.
list_es_module_versions() {
  local module=$1
  ./gradlew ":${module}:printEsVersionsForModule" --quiet </dev/null
}

# Builds the module's base version once, verifies bytecode reuse for the newest version,
# then repackages and publishes every supported ES version.
#   $1 mode (upload_pre|release)  $2 ror_version  $3 module
publish_module_versions() {
  local mode=$1 ror_version=$2 module=$3
  local dist_dir="${module}/build/distributions"
  local es_jars_dir
  es_jars_dir=$(mktemp -d)

  trap 'if [ -n "${es_jars_dir:-}" ]; then rm -rf "$es_jars_dir"; fi' RETURN

  local base_version versions
  { read -r base_version; read -r -a versions; } < <(list_es_module_versions "$module")
  if [ -z "$base_version" ]; then
    echo "ERROR: no versions for module $module"
    return 1
  fi

  echo ""
  echo ">>> Module $module: base ES $base_version, ${#versions[@]} version(s): ${versions[*]}"

  if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
    return 1
  fi

  if ! ./gradlew ":${module}:buildRorPluginZip" "-PesVersion=${base_version}" </dev/null; then
    echo "ERROR: base build failed for $module @ $base_version"
    return 1
  fi

  # Publish newest-to-oldest so the most recent version is available first.
  mapfile -t versions < <(printf '%s\n' "${versions[@]}" | tac)

  local version
  for version in "${versions[@]}"; do
    if [ "$version" != "$base_version" ]; then
      if ! ./gradlew ":${module}:repackageRorPluginForVersion" \
            "-PesVersion=${base_version}" "-PtargetVersion=${version}" "-PesJarsDir=${es_jars_dir}" </dev/null; then
        echo "ERROR: repackage failed for $module @ $version"
        return 1
      fi
    fi

    local zip="${dist_dir}/readonlyrest-${ror_version}_es${version}.zip"

    local attempt published=0
    for attempt in 1 2 3; do
      if publish_one_version "$mode" "$ror_version" "$module" "$version" "$zip"; then
        published=1
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "WARN: publish of $module ES $version failed (attempt $attempt/3); backing off..."
        sleep $((attempt * 15))
      fi
    done
    if [ "$published" -ne 1 ]; then
      echo "ERROR: publish of $module ES $version failed after 3 attempts"
      return 1
    fi

    # Skip deleting the base zip — later iterations need it for repackaging.
    if [ "$version" != "$base_version" ]; then
      rm -f "$zip" "${zip}.sha512"
    fi
  done
  # Safe to delete the base zip now that all repackaging is done.
  local base_zip="${dist_dir}/readonlyrest-${ror_version}_es${base_version}.zip"
  rm -f "$base_zip" "${base_zip}.sha512"

  if [ "$mode" = "release" ]; then
    find "$module" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
    docker buildx prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" >/dev/null 2>&1 || true
  fi

  return 0
}

# Pushes the ES+ROR Docker image for one version and creates the git tag. Skips if already tagged.
release_docker_and_tag() {
  local es_version=$1 module=$2

  if docker manifest inspect "docker.elastic.co/elasticsearch/elasticsearch:${es_version}" >/dev/null 2>&1; then
    if ! ./gradlew ":${module}:pushRorDockerImage" "-PesVersion=$es_version" "-PreusePackagedZip" </dev/null; then
      echo "Failed to publish plugin Docker image for ES $es_version"
      return 4
    fi
    # Reclaim the ES base image layers pulled by BuildKit — each version is ~1.5 GB and
    # they don't share layers, so keeping them in the cache has no benefit and exhausts disk.
    docker buildx prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-1GB}" >/dev/null 2>&1 || true
  else
    echo "WARN: Skipping ES+ROR image for $es_version (no Elasticsearch base image in registry)"
  fi
}

# Publishes one already-derived version: S3 upload + (release) Docker image and git tag.
publish_one_version() {
  local mode=$1 ror_version=$2 module=$3 es_version=$4 zip=$5

  local TAG="v${ror_version}_es${es_version}"

  if [ "$mode" = "release" ]; then
    if ! checkTagNotExist "$TAG"; then
      return 0
    fi
  fi

  if ! ci/upload-files-to-s3.sh "$zip" "${zip}.sha512" "${ror_version}/"; then
    echo "ERROR: S3 upload failed for $module ES $es_version"
    return 1
  fi

  if [ "$mode" = "release" ]; then
    if ! release_docker_and_tag "$es_version" "$module"; then
      echo "ERROR: docker release failed for $module ES $es_version"
      return 1
    fi

    tag "$TAG"
  fi

  return 0
}

# Drives all ES modules in a generation through the publish flow, with per-module retry on failure.
# Usage: publish_ror_plugins <es major: 6|7|8|9> <upload_pre|release>
publish_ror_plugins() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: publish_ror_plugins <es major: 6|7|8|9> <upload_pre|release>"
    return 1
  fi
  local es_major=$1 mode=$2
  local ror_version
  ror_version=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1704067200)}"

  local module
  while IFS= read -r module; do
    [ -z "$module" ] && continue

    local attempt
    for attempt in 1 2 3; do
      if time publish_module_versions "$mode" "$ror_version" "$module"; then
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "WARN: module $module failed (attempt $attempt/3), retrying after cleanup..."
        log_disk_usage "before retry cleanup ($module attempt $attempt)"
        cleanup_docker_and_build
        log_disk_usage "after retry cleanup ($module attempt $attempt)"
      else
        echo "ERROR: module $module failed after 3 attempts"
        return 1
      fi
    done
  done < <(list_es_modules "$es_major")
}
