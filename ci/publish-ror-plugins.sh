#!/bin/bash
#
# Module-grouped ROR plugin publishing ("build once per module, repackage the rest").
#
# This file is a function library: it is SOURCED by ci/run-pipeline.sh, which owns the ROR_TASK dispatch
# (upload_pre_es*/release_es* -> publish_ror_plugins_grouped). Kept separate because the publishing flow
# is the most involved part of the pipeline; run-pipeline.sh stays a thin task router.
#
# Depends on functions from ci-lib.sh (checkTagNotExist, tag) and on the Gradle tasks
# downloadEsVersionedDependencyJars / repackageRorPluginForVersion / verifyRepackageBytecodeNewest. Both ci-lib.sh and this
# file are sourced before any ROR_TASK dispatch runs, so cross-references resolve at call time.
#
# Gradle calls here intentionally run on the daemon (NO --no-daemon): the module-grouped flow makes many
# small invocations -- one compile + one repackage per version + guard recompiles/diffs + the push -- and a
# warm daemon amortizes JVM start + configuration across them (measured ~20s -> ~4s per call). The docker
# push tasks already ran on the daemon, so one is resident during the run regardless.

log_disk_usage() {
  local label="${1:-}"
  echo "=== Disk usage ($label) ==="
  df -h / || true
  df -i / || true

  echo "--- Docker ---"
  docker system df || true
  docker ps -a || true
  docker volume ls || true

  echo "--- Workspace build dirs ---"
  du -sh */build 2>/dev/null || true

  echo "--- Temp dirs ---"
  du -sh /tmp 2>/dev/null || true

  echo "--- Gradle ---"
  du -sh "$GRADLE_USER_HOME/caches" 2>/dev/null || du -sh "$HOME/.gradle/caches" 2>/dev/null || true

  echo "=== End disk usage ==="
}

cleanup_docker_and_build() {
  # Exclude the container this script is running inside (prevents self-removal in DinD setups).
  # In Azure Pipelines container jobs, `hostname` is the short container ID used by `docker ps -aq`.
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

# ----------------------------------------------------------------------------------------------------
# Within an esXXx module the compiled bytecode is identical across the module's patch range, so instead
# of recompiling for every ES version we compile ONCE (at the oldest supported version) and derive every
# other version's zip by swapping only the per-version bits (descriptor, in-jar build-info, and the
# ES-versioned dependency jars). See the Gradle downloadEsVersionedDependencyJars +
# repackageRorPluginForVersion tasks. A per-module bytecode guard (verifyRepackageBytecodeNewest) proves
# the reuse is safe before any release is published.
# ----------------------------------------------------------------------------------------------------

# Emits one line per module in the given ES generation (major), newest module first:
#   "<module> <target version> [<target version> ...]"   (versions newest-first)
# Each module's versions come from its own supportedEsVersions gradle property (the single source of truth);
# the printEsModuleGroups task already groups + orders them, so this is just a thin wrapper.
build_module_groups() {
  local es_major=$1
  local groups_file
  groups_file=$(mktemp)
  ./gradlew printEsModuleGroups "-PesMajor=$es_major" "-PoutputFile=$groups_file" </dev/null >/dev/null
  cat "$groups_file"
  rm -f "$groups_file"
}


# Builds a module's base once, guards bytecode reuse (release) BEFORE shipping anything, then derives each
# remaining version's zip from the base (no recompile, via the Gradle repackageRorPluginForVersion task) and
# publishes it one by one, with an in-place per-version publish retry (a transient blip never re-recompiles).
#   $1 mode (upload_pre|release)  $2 ror_version  $3 module  $4 es_jars_dir
#   $@(5..) target versions (file order: newest..oldest)
publish_module_group() {
  local mode=$1 ror_version=$2 module=$3 es_jars_dir=$4
  shift 4
  local targets=("$@")
  local dist_dir="${module}/build/distributions"

  # The base version is the OLDEST version we're publishing for this module: it's compiled once (against the
  # lowest-common-denominator ES API) and every other version is derived (repackaged) from it. This matches
  # the Gradle build's baselineEsVersion default (oldest supported). targets arrive newest-first, but sort -V
  # so we never depend on ordering.
  local base_version
  base_version=$(printf '%s\n' "${targets[@]}" | sort -V | head -1)
  if [ -z "$base_version" ]; then
    echo "ERROR: no target versions for $module"
    return 1
  fi

  echo ""
  echo ">>> Module $module: base ES $base_version, ${#targets[@]} target version(s): ${targets[*]}"

  # 1) ONE compile for the module, at its base version. Every other version is derived from this zip without
  #    recompiling (step 2).
  if ! ./gradlew ":${module}:buildRorPluginZip" "-PesVersion=${base_version}" </dev/null; then
    echo "ERROR: base build failed for $module @ $base_version"
    return 1
  fi

  # 2) (release only) Bytecode reuse guard, BEFORE deriving or publishing anything -- this is what decides
  #    whether repackaging is valid for this module at all. Any failure aborts the module without shipping a
  #    single version.
  if [ "$mode" = "release" ]; then
    if ! ./gradlew ":${module}:verifyRepackageBytecodeNewest" </dev/null; then
      return 1
    fi
  fi

  # 3) Guard passed (or skipped for upload_pre) -- now release each version ONE BY ONE: derive its zip from
  #    the base (repackageRorPluginForVersion; the base version's zip already exists from step 1, so it isn't
  #    repackaged), publish it, then free its disk. Repackaging is deterministic and stays OUTSIDE the publish
  #    retry below; only the publish is retried, since the failures that actually happen there are transient
  #    and per-version (Docker Hub rate limits, registry/S3 blips). Already-released versions are skipped
  #    cheaply by release_docker_and_tag's remote tag check, so a retry never re-pushes a shipped version.
  local target
  for target in "${targets[@]}"; do
    if [ "$target" != "$base_version" ]; then
      if ! ./gradlew ":${module}:repackageRorPluginForVersion" \
            "-PesVersion=${base_version}" "-PtargetVersion=${target}" "-PesJarsDir=${es_jars_dir}" </dev/null; then
        echo "ERROR: repackage failed for $module @ $target"
        return 1
      fi
    fi

    local zip="${dist_dir}/readonlyrest-${ror_version}_es${target}.zip"

    local pattempt published=0
    for pattempt in 1 2 3; do
      if publish_one_version "$mode" "$ror_version" "$module" "$target" "$zip"; then
        published=1
        break
      fi
      if [ "$pattempt" -lt 3 ]; then
        # Publish failures here are virtually always transient registry-side errors (Docker Hub rate
        # limit / blip), not local disk: the push streams straight to the registry and `--load`s
        # nothing locally. So just back off and retry -- deliberately WITHOUT pruning, so the retry
        # reuses the already-built buildx cache instead of cold-rebuilding the image. Real disk
        # pressure is bounded at the module boundary (buildx --keep-storage) and, as a last resort,
        # by cleanup_docker_and_build on the module-level retry.
        echo "WARN: publish of $module ES $target failed (attempt $pattempt/3); backing off..."
        sleep $((pattempt * 15))
      fi
    done
    if [ "$published" -ne 1 ]; then
      echo "ERROR: publish of $module ES $target failed after 3 attempts"
      return 1
    fi

    rm -f "$zip" "${zip}.sha512"
  done

  # 4) Bound disk at the MODULE boundary (not per version). The multi-platform builds run in the
  #    docker-container buildx builder (ror_kbn_builder), whose BuildKit cache lives in its own volume
  #    -- `docker system prune` / `docker builder prune` can't reach it, so without this it grows
  #    unbounded across the 34 modules (pulled ES base images + layers, ~GBs each). Trim the CURRENT
  #    builder (which is ror_kbn_builder after the push's `buildx use`) but keep a working set so the
  #    shared ES base + fat ROR layer stay hot for the next module. Working set defaults to 5GB and is
  #    overridable via BUILDX_KEEP_STORAGE (e.g. "20GB" on roomier agents, "0" to clear everything).
  rm -rf "${es_jars_dir:?}/"*
  find "$module" -type d -name build -prune -exec rm -rf {} + 2>/dev/null || true
  docker buildx prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" >/dev/null 2>&1 || true
  return 0
}

# Release-only: build & push the ES+ROR image for one version, then create the git tag. The version's zip is
# already in the module's build/distributions (from repackageRorPluginForVersion), so -PreusePackagedZip
# tells the image build to reuse it instead of recompiling. Mirrors the original release_ror_plugin tail.
release_docker_and_tag() {
  local ror_version=$1 es_version=$2 module=$3
  local TAG="v${ror_version}_es${es_version}"

  if ! checkTagNotExist "$TAG"; then
    return 0
  fi

  if docker manifest inspect "docker.elastic.co/elasticsearch/elasticsearch:${es_version}" >/dev/null 2>&1; then
    if ! ./gradlew ":${module}:pushRorDockerImage" "-PesVersion=$es_version" "-PreusePackagedZip" </dev/null; then
      echo "Failed to publish plugin Docker image for ES $es_version"
      return 4
    fi
  else
    echo "WARN: Skipping ES+ROR image for $es_version (no Elasticsearch base image in registry)"
  fi

  tag "$TAG"
}

# Publishes ONE already-derived version: S3 upload + (release) docker image & git tag. No recompile.
# Returns non-zero on failure so the per-version retry in publish_module_group can re-attempt just this
# version. Idempotent on re-run: S3 overwrites, and release_docker_and_tag skips versions already tagged
# on the remote.
publish_one_version() {
  local mode=$1 ror_version=$2 module=$3 target=$4 zip=$5

  if ! ci/upload-files-to-s3.sh "$zip" "${zip}.sha512" "${ror_version}/"; then
    echo "ERROR: S3 upload failed for $module ES $target"
    return 1
  fi

  if [ "$mode" = "release" ]; then
    if ! release_docker_and_tag "$ror_version" "$target" "$module"; then
      echo "ERROR: docker release failed for $module ES $target"
      return 1
    fi
  fi

  return 0
}

# Drives a whole generation file through the module-grouped flow. Two-level retry: transient per-version
# publish failures are absorbed in place by publish_module_group (no recompile); only a build/download/guard
# failure bubbles up here and triggers a heavy cleanup + full module rebuild.
publish_ror_plugins_grouped() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: publish_ror_plugins_grouped <es major: 6|7|8|9> <upload_pre|release>"
    return 1
  fi
  local es_major=$1 mode=$2
  local ror_version
  ror_version=$(grep '^pluginVersion=' gradle.properties | awk -F= '{print $2}')

  # Pin SOURCE_DATE_EPOCH for the whole run so BuildKit normalises every build-time timestamp (file
  # mtimes + the parent dirs `COPY --link` synthesises) to a constant. This is what makes the
  # version-independent fat ROR docker layer keep ONE content digest across every patch version, so the
  # registry stores/pushes that ~95 MB blob once per module instead of once per version. The Gradle push
  # tasks read this env (with a constant fallback); exporting the commit time here just gives the images a
  # meaningful, reproducible "created" date. Must be set before the first gradlew call so the daemon (if
  # any) inherits it.
  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct 2>/dev/null || echo 1704067200)}"

  local es_jars_dir
  es_jars_dir=$(mktemp -d)

  local groups
  groups=$(build_module_groups "$es_major")

  local line
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    local module
    module=$(echo "$line" | awk '{print $1}')
    local targets
    read -r -a targets <<< "$(echo "$line" | cut -d' ' -f2-)"

    local attempt
    for attempt in 1 2 3; do
      if time publish_module_group "$mode" "$ror_version" "$module" "$es_jars_dir" "${targets[@]}"; then
        break
      fi
      if [ "$attempt" -lt 3 ]; then
        echo "WARN: module $module failed (attempt $attempt/3), retrying after cleanup..."
        log_disk_usage "before retry cleanup ($module attempt $attempt)"
        cleanup_docker_and_build
        log_disk_usage "after retry cleanup ($module attempt $attempt)"
      else
        echo "ERROR: module $module failed after 3 attempts"
        rm -rf "$es_jars_dir"
        return 1
      fi
    done
  done <<< "$groups"

  rm -rf "$es_jars_dir"
}
