#!/bin/bash -e

# This file's OWN directory — used to locate the sibling scripts it shells out to (s3-uploader.sh).
# BASH_SOURCE, not $0: $0 is the script that INVOKED us, which is a different directory whenever this
# lib is sourced rather than run (`source ci/ci-lib.sh && reap_ci_job_containers` resolved it to ".").
CI_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Reads one key from gradle.properties, which holds the build's own values. The file sits beside this
# one, so the caller's working directory does not matter.
#
# An absent key fails. An empty value would name a wrong image or a wrong version, and nothing later
# would show the cause. Every reader of the file goes through here: a second parser drifts.
#
# The value is everything after the first `=`, because a value such as `-Dkey=value` holds one too.
# A key that the file states twice keeps the last line, which is the value gradle itself reads.
gradle_property() {
  local key=$1 file=$CI_DIR/../gradle.properties value
  value=$(awk -F= -v k="$key" '$1==k {line=$0} END {sub(/^[^=]*=/, "", line); print line}' "$file")
  if [ -z "$value" ]; then
    echo "$file has no $key" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

docker_image_exists() {
  # This answer decides whether we skip an expensive rebuild, so it must come from Docker Hub and
  # never from a cache: a cache can hold a stale answer for a tag we pushed a moment ago. `docker
  # manifest inspect` reads the name it is given, and this one carries no mirror, so the answer
  # comes from Docker Hub. Keep it that way.
  docker manifest inspect "$1" >/dev/null 2>&1
}

# Runs a command again after a failure. The delay doubles each time.
#
#   retry_with_backoff [--retry-if <function>] <command> [arg ...]
#
# Without --retry-if it repeats every failure. With it, the function decides. The function gets two
# arguments: a file that holds the output of the command, and the status of the command. A status
# of 0 from the function means "repeat this".
#
#   retry_with_backoff --retry-if is_docker_registry_error ./gradlew pushRorDockerImage
#
# --retry-if also captures the output, so the command then runs in a pipeline, thus in a subshell,
# and its standard error joins its standard output. Give it an external command. A shell function
# that sets a variable would lose the value.
retry_with_backoff() {
  local attempts=${ROR_RETRY_ATTEMPTS:-3}
  local delay=${ROR_RETRY_DELAY_SECONDS:-15}
  local attempt=1
  local retry_if=""
  local status log

  while [ "$#" -gt 0 ]; do
    case $1 in
      --retry-if)
        retry_if=$2
        shift 2
        ;;
      --)
        shift
        break
        ;;
      *)
        break
        ;;
    esac
  done

  if [ "$#" -eq 0 ]; then
    echo "[CI] retry_with_backoff needs a command."
    return 2
  fi
  if ! [[ $attempts =~ ^[0-9]+$ ]] || [ "$attempts" -lt 1 ]; then
    echo "[CI] ROR_RETRY_ATTEMPTS must be a positive integer, got '$attempts'."
    return 2
  fi
  if ! [[ $delay =~ ^[0-9]+$ ]]; then
    echo "[CI] ROR_RETRY_DELAY_SECONDS must be a non-negative integer, got '$delay'."
    return 2
  fi
  if [ -n "$retry_if" ] && ! declare -F "$retry_if" >/dev/null; then
    echo "[CI] --retry-if needs the name of a function, got '$retry_if'."
    return 2
  fi

  log=""
  if [ -n "$retry_if" ]; then
    log=$(mktemp) || return 2
  fi

  while true; do
    if [ -n "$log" ]; then
      # tee keeps the output on the console. PIPESTATUS holds the status of the command itself, not
      # the status of tee.
      "$@" 2>&1 | tee "$log"
      status=${PIPESTATUS[0]}
    else
      "$@"
      status=$?
    fi

    if [ "$status" -eq 0 ]; then
      [ -n "$log" ] && rm -f "$log"
      return 0
    fi
    if [ -n "$retry_if" ] && ! "$retry_if" "$log" "$status"; then
      echo "[CI] '$1' failed, and this failure is not one to repeat."
      rm -f "$log"
      return "$status"
    fi
    if [ "$attempt" -ge "$attempts" ]; then
      echo "[CI] '$1' failed on all $attempts attempts."
      [ -n "$log" ] && rm -f "$log"
      return "$status"
    fi
    echo "[CI] '$1' failed (attempt $attempt of $attempts). Next attempt in ${delay}s."
    sleep "$delay"
    attempt=$((attempt + 1))
    delay=$((delay * 2))
  done
}

# A --retry-if function for a command that pushes or pulls a Docker image. True when the output
# holds a registry error, which another attempt can clear. A compile error, a wrong -PesVersion and
# a broken Dockerfile give the same result every time, and both commands we retry build a
# multi-arch image before they push, so a repeat of such a failure costs two more full builds.
is_docker_registry_error() {
  grep -Eqi \
    'toomanyrequests|429 Too Many Requests|received unexpected HTTP status: 4?5[0-9][0-9]|unexpected status: 5[0-9][0-9]|50[0234] (Internal Server Error|Bad Gateway|Service Unavailable|Gateway Time-?out)|TLS handshake timeout|i/o timeout|connection reset by peer|unexpected EOF|net/http: request canceled' \
    "$1"
}

# Force-remove every container belonging to THIS CI job, scoped by the ror.ci-job=$ROR_CI_JOB_ID label so we
# never touch a sibling CI job sharing the self-hosted Docker daemon. Single source of truth for "kill
# this CI job's containers" — used by run-pipeline.sh's SIGTERM trap, the pipeline's always() cleanup
# step, and the standalone orphan reaper. No-op if ROR_CI_JOB_ID is unset or nothing matches.
reap_ci_job_containers() {
  [ -n "${ROR_CI_JOB_ID:-}" ] || return 0
  local ids
  ids=$(docker ps -aq --filter "label=ror.ci-job=$ROR_CI_JOB_ID" 2>/dev/null)
  [ -n "$ids" ] && docker rm -f $ids 2>/dev/null || true
}

ES_DEV_IMAGE_REPO="$(gradle_property dockerImageNamespace)/elasticsearch-readonlyrest-dev" || exit 1

# Copies a registry image manifest to a new tag without pulling/rebuilding (multi-platform safe).
retag_dev_image() {
  if [ "$#" -ne 2 ]; then
    echo "Usage: retag_dev_image <source tag> <target tag>"
    return 1
  fi

  local SOURCE_TAG=$1
  local TARGET_TAG=$2
  echo ">>> Tagging ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG} as ${ES_DEV_IMAGE_REPO}:${TARGET_TAG}"
  docker buildx imagetools create \
    -t "${ES_DEV_IMAGE_REPO}:${TARGET_TAG}" \
    "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"
}

# Build & publish the ROR ES pre-build Docker image for the given ES version.
#
# To avoid rebuilding when the sources have not changed, every build is frozen under an immutable,
# source-identified tag <esVersion>-ror-<gitShortSha>. Before building we probe that tag in the registry:
# if it already exists the Gradle build (the expensive build+push) is skipped. Setting FORCE_REBUILD=true
# bypasses the skip.
#
# Tags produced (all but the Gradle push are cheap registry-side manifest copies):
#   - <esVersion>-ror-<pluginVersion>   canonical "latest", pushed by Gradle (only on a real build)
#   - <esVersion>-ror-<gitShortSha>     immutable source identity, frozen from canonical (probed for the skip)
#   - <esVersion>-ror-<imageTag>        optional alias to the source image, when an image tag arg is given
publish_ror_es_prebuild_plugin() {
  if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: publish_ror_es_prebuild_plugin <ES version> [image tag]"
    return 1
  fi

  local ES_VERSION=$1
  local IMAGE_TAG="${2:-}"

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "Invalid ES version format. Expected format: X.Y.Z"
    return 2
  fi

  if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon not running or not logged in"
    return 3
  fi

  local ROR_VERSION GIT_SHA
  ROR_VERSION=$(gradle_property pluginVersion) || return 1
  GIT_SHA=$(git rev-parse --short HEAD)

  local CANONICAL_TAG="${ES_VERSION}-ror-${ROR_VERSION}"
  local SOURCE_TAG="${ES_VERSION}-ror-${GIT_SHA}"

  echo ""
  echo "PUBLISHING ROR PRE-BUILD for ES $ES_VERSION (source ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}):"

  # Azure boolean params expand as True/False, so normalize case before comparing.
  local FORCE_REBUILD_NORM
  FORCE_REBUILD_NORM=$(echo "${FORCE_REBUILD:-false}" | tr '[:upper:]' '[:lower:]')

  if [ "$FORCE_REBUILD_NORM" != "true" ] && docker_image_exists "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"; then
    echo ">>> Sources unchanged (image for this commit already published), skipping build"
  else
    # This build pulls base images and pushes the result, so a registry can answer 429. Only such
    # a failure is repeated. A broken build fails at once.
    if ! retry_with_backoff --retry-if is_docker_registry_error \
         ./gradlew publishEsRorPreBuildDockerImage "-PesVersion=$ES_VERSION" </dev/null; then
      echo "Failed to publish plugin prebuild Docker image"
      return 4
    fi
    # Freeze this build under its immutable source-identity tag so future runs can detect & skip it.
    if ! retag_dev_image "$CANONICAL_TAG" "$SOURCE_TAG"; then
      echo "Failed to tag prebuild Docker image as ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"
      return 5
    fi
  fi

  # Apply the optional caller-supplied alias on BOTH paths (built or skipped). The caller fetches the image
  # by this tag, so it must exist and point at this commit's image: we always derive it from SOURCE_TAG
  # (the commit identity), and the imagetools exit status guarantees it was created before we return.
  if [ -n "$IMAGE_TAG" ]; then
    if ! retag_dev_image "$SOURCE_TAG" "${ES_VERSION}-ror-${IMAGE_TAG}"; then
      echo "Failed to tag prebuild Docker image as ${ES_DEV_IMAGE_REPO}:${ES_VERSION}-ror-${IMAGE_TAG}"
      return 6
    fi
  fi
}

checkTagNotExist() {
  GIT_TAG="$1"

  # Check only the remote to avoid false positives from stale local tags left by a
  # previous attempt that created the local tag but failed before pushing it.
  if git ls-remote --tags origin "refs/tags/${GIT_TAG}" 2>/dev/null | grep -q "${GIT_TAG}"; then
    echo "Git tag $GIT_TAG already exists on remote, exiting."
    return 1
  fi
}

tag() {
  GIT_TAG="$1"

  checkTagNotExist "$GIT_TAG" || return 0

  echo "Tagging as $GIT_TAG"
  git config --global push.default matching
  git config --global user.email "support@readonlyrest.com"
  git config --global user.name "CI"
  # -f overwrites any stale local tag from a previous failed push attempt
  git tag -fa "$GIT_TAG" -m "Generated tag from CI build $TRAVIS_BUILD_NUMBER"
  git push origin "$GIT_TAG"
  return 0
}

# Upload a file to an S3-compatible store using the SigV4 curl uploader.
#
# The store is selected by the 3rd arg (default ARTIFACTS) and resolves the matching
# ROR_<STORE>_STORE_* env vars, so the same logic serves any store: each one keeps its own
# endpoint, credentials, bucket, region and path-prefix under its own name.
#
# This is the ONE place that turns a store name into a set of values. Anything uploading to a
# ROR store goes through it rather than resolving ROR_<STORE>_STORE_* itself — a second copy of
# this resolution is how the two sides of the libs store drifted apart before (see LibsStore).
#
# For the libs store the caller (ror-tools, via LibsStore) always passes explicit values, so the
# fallbacks below never apply to it; they only cover a store whose vars are partly unset.
#
# Two entry points, differing only in what the destination MEANS:
#   upload_using_aws_s3_uploader        <file> <dir>  [store] [mime]  — key is <dir>/<basename>
#   upload_using_aws_s3_uploader_to_key <file> <key>  [store] [mime]  — key is exactly <key>
# The mime type is optional; without it the uploader guesses (which needs `file` on the runner).
function _upload_to_s3_target {
  local LOCAL_FILE="$1"
  local S3_TARGET="$2"
  local STORE="${3:-ARTIFACTS}"
  local MIME="${4:-}"
  local BUCKET PATH_PREFIX

  if [[ ! -f "$LOCAL_FILE" ]]; then
    echo "ERROR: artifact to upload not found (or not a regular file): $LOCAL_FILE"
    return 1
  fi

  # One credential set serves every store; only the key prefix differs, so the store name
  # selects a path (ROR_S3_PATH_ARTIFACTS / _LIBS / _E2E_REPORTS) and nothing else.
  local PREFIX_VAR="ROR_S3_PATH_${STORE}"

  local ENDPOINT="${ROR_S3_ENDPOINT_URL-}"
  local AK="${ROR_S3_ACCESS_KEY_ID-}"
  local SK="${ROR_S3_SECRET_ACCESS_KEY-}"
  local REGION="${ROR_S3_REGION-}"
  BUCKET="${ROR_S3_BUCKET-}"; BUCKET="${BUCKET:-beshu}"
  PATH_PREFIX="${!PREFIX_VAR-}"
  [ -n "$PATH_PREFIX" ] && PATH_PREFIX="${PATH_PREFIX%/}/"

  S3_ENDPOINT_URL="$ENDPOINT" \
    "$CI_DIR"/s3-uploader.sh \
      "$AK" "$SK" \
      "$BUCKET@${REGION:-us-east-1}" "$LOCAL_FILE" "${PATH_PREFIX}${S3_TARGET}" ${MIME:+"$MIME"}
}

# Upload into a directory: the uploader appends the file's basename to it.
function upload_using_aws_s3_uploader {
  local S3_DIR
  S3_DIR=$(echo "$2" | sed 's:/*$::')
  _upload_to_s3_target "$1" "${S3_DIR}/" "${3:-ARTIFACTS}" "${4:-}"
}

# Upload to an exact key. For callers that mirror a directory tree, where the key is the file's
# path within that tree and not its basename. Its caller is the e2e Cypress report uploader
# (RORDEV-1229, change/RORDEV-1229_run_e2e_tests), which today resolves ROR_<STORE>_STORE_* with its
# own inline copy — the duplication this file exists to remove. Unused until that branch lands.
function upload_using_aws_s3_uploader_to_key {
  _upload_to_s3_target "$1" "$2" "${3:-ARTIFACTS}" "${4:-}"
}

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