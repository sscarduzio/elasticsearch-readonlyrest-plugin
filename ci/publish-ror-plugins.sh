#!/bin/bash
#
# Function library for publishing ROR plugins (build once per ES module, repackage for each supported version).
# Sourced by ci/run-pipeline.sh.

cleanup_docker_and_build() {
  # On the shared box the daemon also serves the readonlyrest_kbn runners, which hold running ELK
  # stacks. The full sweep below would delete them, so reclaim only what this build left behind.
  if is_shared_docker_host; then
    ci_log "Shared docker host, so this cleanup removes only dangling images and the build cache."
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

# Prints the versions from $3.. that still need publishing, one per line. It leaves out a version
# whose tag the file $2 holds. An empty file keeps every version, which is what upload_pre hands
# over: that mode writes no tag.
#   $1 ror_version  $2 file of origin tags  $3.. versions
pending_versions() {
  local ror_version=$1 origin_tags_file=$2
  shift 2
  local versions=("$@") version git_tag

  # An unreadable file would leave every version pending, and a release would publish them all again.
  if [ ! -r "$origin_tags_file" ]; then
    ci_log "Cannot read the tag list at $origin_tags_file."
    return 1
  fi

  for version in "${versions[@]}"; do
    git_tag=$(release_tag "$ror_version" "$version")
    if grep -qFx "$git_tag" "$origin_tags_file"; then
      ci_log "$git_tag is already on origin, so this run skips ES $version."
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
  # The scratch space of one module run: the ES jars of the repackage, and the tag list. The trap
  # removes it on every exit path, an early return included.
  local module_tmp_dir
  module_tmp_dir=$(mktemp -d)

  trap 'if [ -n "${module_tmp_dir:-}" ]; then rm -rf "$module_tmp_dir"; fi' RETURN

  local base_version versions
  { read -r base_version; read -r -a versions; } < <(list_es_module_versions "$module")
  if [ -z "$base_version" ]; then
    ci_log "Module $module has no ES version."
    return 1
  fi

  ci_log "Module $module builds from base ES $base_version, and publishes ${#versions[@]} versions: ${versions[*]}."

  # Publish newest-to-oldest so the most recent version is available first.
  mapfile -t versions < <(printf '%s\n' "${versions[@]}" | tac)

  # Fresh on every attempt: a retry of this module must see the tags an earlier attempt already
  # wrote, or it rebuilds and re-uploads a version this run already published.
  local origin_tags_file="$module_tmp_dir/origin-tags.txt"
  : > "$origin_tags_file"
  if [ "$mode" = release ]; then
    origin_tags "$(release_tag "$ror_version" '*')" > "$origin_tags_file" || return 1
  fi

  # Checking is a git query. Building and repackaging costs minutes. Filtering here, before any
  # build, skips that cost for every version already published.
  local pending_output
  pending_output=$(pending_versions "$ror_version" "$origin_tags_file" "${versions[@]}") || return 1
  local -a pending=()
  [ -n "$pending_output" ] && mapfile -t pending <<< "$pending_output"

  if [ "${#pending[@]}" -eq 0 ]; then
    ci_log "Module $module has every version on origin, so this run builds nothing."
    return 0
  fi

  if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
    return 1
  fi

  if ! ./gradlew ":${module}:buildRorPluginZip" "-PesVersion=${base_version}" </dev/null; then
    ci_log "The base build of $module failed at ES $base_version."
    return 1
  fi

  local version
  for version in "${pending[@]}"; do
    if [ "$version" != "$base_version" ]; then
      if ! ./gradlew ":${module}:repackageRorPluginForVersion" \
            "-PesVersion=${base_version}" "-PtargetVersion=${version}" "-PesJarsDir=${module_tmp_dir}" </dev/null; then
        ci_log "The repackage of $module failed at ES $version."
        return 1
      fi
    fi

    local zip="${dist_dir}/readonlyrest-${ror_version}_es${version}.zip"

    if ! publish_version_artifacts "$mode" "$ror_version" "$module" "$version" "$zip"; then
      ci_log "The publish of $module ES $version failed."
      return 1
    fi

    if [ "$mode" = "release" ] && ! tag "$(release_tag "$ror_version" "$version")"; then
      ci_log "Cannot tag $module ES $version."
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
    ci_log "Elastic published no base image for ES $es_version, so this run skips the ES+ROR image."
    return 0
  fi

  # This build pulls base images and pushes the result, so a registry can answer 429. Only such
  # a failure is repeated. A broken build fails at once.
  if ! retry_with_backoff --retry-if is_docker_registry_error \
       ./gradlew ":${module}:pushRorDockerImage" "-PesVersion=$es_version" "-PreusePackagedZip" </dev/null; then
    ci_log "Cannot publish the ROR Docker image for ES $es_version."
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
  if ! retry_with_backoff ci/upload-files-to-s3.sh "$zip" "${zip}.sha512" "${ror_version}/"; then
    ci_log "The S3 upload of $module ES $es_version failed."
    return 1
  fi

  if [ "$mode" = "release" ]; then
    if ! push_ror_docker_image "$es_version" "$module"; then
      ci_log "The docker release of $module ES $es_version failed."
      return 1
    fi
  fi

  return 0
}

# Drives all ES modules in a generation through the publish flow, with per-module retry on failure.
# Usage: publish_es_major <es major> <upload_pre|release>
publish_es_major() {
  if [ "$#" -ne 2 ]; then
    ci_log "Usage: publish_es_major <es major> <upload_pre|release>"
    return 1
  fi
  local es_major=$1 mode=$2
  local ror_version
  ror_version=$(gradle_property pluginVersion) || return 1

  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1704067200)}"

  # Capture first (process substitution would swallow a module-discovery failure into plain EOF).
  local modules
  modules=$(list_es_modules "$es_major") || { ci_log "Cannot list the es${es_major}x modules."; return 1; }

  if [ -z "$modules" ]; then
    ci_log "No es${es_major}x module exists, so there is nothing to $mode."
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
        ci_log "Module $module failed on attempt $attempt of 3. The next attempt starts after a cleanup."
        log_disk_usage "before retry cleanup ($module attempt $attempt)"
        cleanup_docker_and_build
        log_disk_usage "after retry cleanup ($module attempt $attempt)"
      else
        ci_log "Module $module failed on all 3 attempts."
        return 1
      fi
    done
  done <<< "$modules"
}
