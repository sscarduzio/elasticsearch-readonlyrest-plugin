#!/bin/bash
#
# Function library for publishing ROR plugins (build once per ES module, repackage for each supported version).
# Sourced by ci/run-pipeline.sh.

cleanup_docker_and_build() {
  # On the shared box the daemon also serves the readonlyrest_kbn runners, which hold running ELK
  # stacks. The full sweep below would delete them, so reclaim only what this build left behind.
  if is_shared_docker_host; then
    echo ">>> shared docker host: pruning only dangling images and build cache"
    docker image prune -f || true
    docker builder prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" || true
    find . -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
    return 0
  fi

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
# The task writes the list to build/es-modules/es<major>x.txt; read THAT, never gradle stdout
# (configuration-time build-script logging can pollute it even under --quiet).
list_es_modules() {
  local es_major=$1
  local modules_file="build/es-modules/es${es_major}x.txt"
  # rm first: a failed gradle run must yield an error, never a stale list from a previous run.
  rm -f "$modules_file"
  ./gradlew printEsModules "-PesMajor=$es_major" --quiet </dev/null >&2 || return 1
  cat "$modules_file"
}

# Emits two lines: the base ES version on line 1, all supported versions space-separated on line 2.
list_es_module_versions() {
  local module=$1
  local versions_file="${module}/build/es-modules/versions.txt"
  rm -f "$versions_file"
  ./gradlew ":${module}:printEsVersionsForModule" --quiet </dev/null >&2 || return 1
  cat "$versions_file"
}

release_tag() {
  printf 'v%s_es%s\n' "$1" "$2"
}

# Prints, newline-separated, every release tag of $2 that exists on origin. One query for the
# whole ES major, not one per version — with 17 versions in the ES8 major, that is 17 round
# trips down to 1.
#   $1 ror_version
origin_release_tags() {
  local ror_version=$1 refs
  refs=$(git ls-remote --tags origin "refs/tags/v${ror_version}_es*") || {
    echo "ERROR: cannot read the tags of origin for v${ror_version}." >&2
    return 1
  }
  printf '%s\n' "$refs" | awk '{print $2}' | sed -e 's#^refs/tags/##' -e 's/\^{}$//' | sort -u
}

# Prints the versions from $4.. that still need publishing, one per line. Release mode skips any
# version whose tag is already in $3. upload_pre never tags, so it keeps every version there and
# ignores $3.
#   $1 mode  $2 ror_version  $3 origin_tags  $4.. versions
pending_versions() {
  local mode=$1 ror_version=$2 origin_tags=$3
  shift 3
  local versions=("$@") version git_tag

  if [ "$mode" != release ]; then
    printf '%s\n' "${versions[@]}"
    return 0
  fi

  for version in "${versions[@]}"; do
    git_tag=$(release_tag "$ror_version" "$version")
    if grep -qFx "$git_tag" <<< "$origin_tags"; then
      echo ">>> $git_tag is already on origin. Skipping ES $version." >&2
    else
      printf '%s\n' "$version"
    fi
  done
}

# Builds the module's base version once and verifies bytecode reuse for the newest version.
# Then it repackages and publishes each ES version. Release mode skips any version origin
# already tagged. upload_pre publishes every version regardless.
#   $1 mode (upload_pre|release)  $2 ror_version  $3 module
publish_module() {
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

  # Publish newest-to-oldest so the most recent version is available first.
  mapfile -t versions < <(printf '%s\n' "${versions[@]}" | tac)

  # Fresh on every attempt: a retry of this module must see the tags an earlier attempt already
  # wrote, or it rebuilds and re-uploads a version this run already published.
  local origin_tags=""
  if [ "$mode" = release ]; then
    origin_tags=$(origin_release_tags "$ror_version") || return 1
  fi

  # Checking is a git query. Building and repackaging costs minutes. Filtering here, before any
  # build, skips that cost for every version already published.
  local pending_output
  pending_output=$(pending_versions "$mode" "$ror_version" "$origin_tags" "${versions[@]}") || return 1
  local -a pending=()
  [ -n "$pending_output" ] && mapfile -t pending <<< "$pending_output"

  if [ "${#pending[@]}" -eq 0 ]; then
    echo ">>> Module $module: every version is already published. Nothing to build."
    return 0
  fi

  if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
    return 1
  fi

  if ! ./gradlew ":${module}:buildRorPluginZip" "-PesVersion=${base_version}" </dev/null; then
    echo "ERROR: base build failed for $module @ $base_version"
    return 1
  fi

  local version
  for version in "${pending[@]}"; do
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
      if publish_version_artifacts "$mode" "$ror_version" "$module" "$version" "$zip"; then
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

    if [ "$mode" = "release" ] && ! tag "$(release_tag "$ror_version" "$version")"; then
      echo "ERROR: cannot tag $module ES $version"
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

# Pushes the ES+ROR Docker image for one version
push_ror_docker_image() {
  local es_version=$1 module=$2
  local base_image="docker.elastic.co/elasticsearch/elasticsearch:${es_version}"
  local base_image_state

  base_image_state=$(docker_image_state "$base_image") || return 4
  if [ "$base_image_state" = absent ]; then
    # Elastic published no image for some ES patch versions. There is no base to build on, so a
    # skip is correct here, not an error.
    echo "WARN: Skipping ES+ROR image for $es_version (no Elasticsearch base image in registry)"
    return 0
  fi

  # This build pulls base images and pushes the result, so a registry can answer 429. Only such
  # a failure is repeated. A broken build fails at once.
  if ! retry_with_backoff --retry-if is_docker_registry_error \
       ./gradlew ":${module}:pushRorDockerImage" "-PesVersion=$es_version" "-PreusePackagedZip" </dev/null; then
    echo "Failed to publish plugin Docker image for ES $es_version"
    return 4
  fi
  # Reclaim the ES base image layers pulled by BuildKit — each version is ~1.5 GB and
  # they don't share layers, so keeping them in the cache has no benefit and exhausts disk.
  docker buildx prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-1GB}" >/dev/null 2>&1 || true
}

# Publishes one already-derived version: S3 upload + (release) Docker image. Tagging is the
# caller's job.
publish_version_artifacts() {
  local mode=$1 ror_version=$2 module=$3 es_version=$4 zip=$5

  # publish always - even if this is not a release
  if ! ci/upload-files-to-s3.sh "$zip" "${zip}.sha512" "${ror_version}/"; then
    echo "ERROR: S3 upload failed for $module ES $es_version"
    return 1
  fi

  if [ "$mode" = "release" ]; then
    if ! push_ror_docker_image "$es_version" "$module"; then
      echo "ERROR: docker release failed for $module ES $es_version"
      return 1
    fi
  fi

  return 0
}

# Drives all ES modules in a generation through the publish flow, with per-module retry on failure.
# Usage: publish_es_major <es major> <upload_pre|release>
publish_es_major() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: publish_es_major <es major> <upload_pre|release>"
    return 1
  fi
  local es_major=$1 mode=$2
  local ror_version
  ror_version=$(gradle_property pluginVersion) || return 1

  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1704067200)}"

  # Capture first (process substitution would swallow a module-discovery failure into plain EOF).
  local modules
  modules=$(list_es_modules "$es_major") || { echo "ERROR: cannot list es${es_major}x modules"; return 1; }

  if [ -z "$modules" ]; then
    echo "ERROR: no es${es_major}x module to $mode; ES $es_major has no module owning it"
    return 1
  fi

  local module
  while IFS= read -r module; do
    [ -z "$module" ] && continue

    local attempt
    for attempt in 1 2 3; do
      if time publish_module "$mode" "$ror_version" "$module"; then
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
  done <<< "$modules"
}
