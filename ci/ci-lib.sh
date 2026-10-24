#!/bin/bash -e

# This file's OWN directory — used to locate the sibling scripts it shells out to (s3-uploader.sh).
# BASH_SOURCE, not $0: $0 is the script that INVOKED us, which is a different directory whenever this
# lib is sourced rather than run (`source ci/ci-lib.sh && reap_ci_job_containers` resolved it to ".").
CI_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# shellcheck source=ci/runner-detect.sh
source "$CI_DIR/runner-detect.sh"

# shellcheck source=ci/log.sh
source "$CI_DIR/log.sh"

# The repository this build works on. CI_DIR sits in it.
ROR_REPO_ROOT=$(cd "$CI_DIR/.." && pwd)

# A job that runs in a container works as root, while the checkout keeps the user id of the runner.
# git refuses every command in a working tree of another user ("dubious ownership"), so a caller of
# `git rev-parse` gets no commit and the job stops. The exception makes git usable again. It goes to
# the global config of the job, and each job gets a new container, so no machine keeps it.
if ! git -C "$ROR_REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  git config --global --add safe.directory "$ROR_REPO_ROOT" >/dev/null 2>&1 || true
fi


# Reads one key from gradle.properties, which holds the build's own values. The file sits beside this
# one, so the caller's working directory does not matter.
#
# An absent key fails. An empty value would name a wrong image or a wrong version, and nothing later
# would show the cause. Every reader of the file goes through here: a second parser drifts.
#
# The value is everything after the first `=`, because a value such as `-Dkey=value` holds one too.
# A key that the file states twice keeps the last line, which is the value gradle itself reads.
gradle_property() {
  local key=$1 file=$ROR_REPO_ROOT/gradle.properties value
  value=$(awk -F= -v k="$key" '$1==k {line=$0} END {sub(/^[^=]*=/, "", line); print line}' "$file")
  if [ -z "$value" ]; then
    ci_log "$file has no key '$key'."
    return 1
  fi
  printf '%s\n' "$value"
}

# The gradle args a Windows runner adds to every ./gradlew call. A Linux runner adds none.
# The toolchains image bakes its JDK paths into /opt/gradle-home/gradle.properties, which a Windows
# job never reads. These args state the Windows answer anyway, for a caller that does inherit it.
# One arg per line, for a caller to read into an array.
windows_gradle_args() {
  is_windows || return 0
  printf '%s\n' \
    --build-cache \
    -Dorg.gradle.java.installations.paths= \
    -Dorg.gradle.java.installations.auto-download=true
}

# Prints every JVM crash log under the working tree. A crashed JVM writes one and says nothing on
# stdout, so a leg that ends with no test failure leaves this as its only evidence.
dump_hs_err_files() {
  find . -name 'hs_err*' -type f -exec cat {} + 2>/dev/null || true
}

# The ES-major matrix, from the file `printEsMajors` writes. Run that task first, in the gradle call
# the job already makes. One reader: a workflow's shell has no test, so two hand-written copies of
# this format drift without a failure.
es_majors_matrix() {
  local file=$ROR_REPO_ROOT/build/es-modules/es-majors.txt
  [ -s "$file" ] || { ci_log "printEsMajors wrote no ES major to $file."; return 1; }
  jq -Rc -s 'split("\n") | map(select(length > 0)) | {include: map({ES_MAJOR: .})}' < "$file"
}

# Prints `true` or `false`: is the configured pluginVersion a pre-release? The `isPreReleaseVersion`
# gradle task holds the only implementation of that rule. Take the last line, because
# configuration-time logging pollutes stdout even under --quiet, and fail on any other word.
is_pre_release_version() {
  local value
  value=$(cd "$ROR_REPO_ROOT" && ./gradlew --no-daemon -q isPreReleaseVersion | tail -n 1) || return 1
  case "$value" in
    true|false) printf '%s\n' "$value" ;;
    *) ci_log "isPreReleaseVersion must print true or false. It printed '$value'."; return 1 ;;
  esac
}

# True when HEAD carries the same tree as master's tip: on a develop push, only a merge-back does
# that. The tree, not the commit, because a merge-back through a PR gets a merge commit.
# Fetches master from origin; needs no token on a public repo. False on any failure.
is_merge_back_of_master() {
  local master_tree here_tree
  git fetch --quiet --no-tags --depth=1 origin master 2>/dev/null || return 1
  master_tree=$(git rev-parse --verify --quiet 'FETCH_HEAD^{tree}') || return 1
  here_tree=$(git rev-parse --verify --quiet 'HEAD^{tree}') || return 1
  ci_log "HEAD tree $here_tree, master tree $master_tree."
  [ "$here_tree" = "$master_tree" ]
}

# Asks a registry about one image. Prints `present` or `absent`, and fails when it cannot ask, so
# no caller reads an unreachable registry as an image that does not exist.
#
# Pass a name that carries no mirror: a cache can hold a stale answer for a tag we pushed a moment ago.
docker_image_state() {
  local image=$1 log

  log=$(mktemp) || return 1
  # --retry-if merges the output of the command into its standard output, so the first redirect
  # catches both streams. The second one drops the messages of retry_with_backoff: an image Elastic
  # never published makes the probe fail, and that is an answer here, not a fault. The lines below
  # report what the probe found, on every path.
  if retry_with_backoff --retry-if is_docker_registry_error \
       docker manifest inspect "$image" >"$log" 2>/dev/null; then
    rm -f "$log"
    printf 'present\n'
    return 0
  fi
  if grep -Eqi 'no such manifest|manifest unknown|manifest for .+ not found' "$log"; then
    rm -f "$log"
    printf 'absent\n'
    return 0
  fi
  ci_log "Cannot read $image from the registry."
  cat "$log" >&2
  rm -f "$log"
  return 1
}

# True only when the registry holds the image, false also when it cannot answer.
docker_image_exists() {
  [ "$(docker_image_state "$1")" = present ]
}

# The label that names the full commit of the sources of a dev image.
ROR_REVISION_LABEL="com.readonlyrest.revision"

# The Go template that describes an image: one line <os>/<arch>[/<variant>] <revision label> for each
# platform. An image with one platform is one image; with more platforms it is a map of images. The
# template needs no jq, which a self-hosted runner may not have.
ROR_IMAGE_DESCRIPTION_TEMPLATE='{{- if eq (slice (printf "%T" .Image) 0 3) "map" -}}
{{- range $platform, $image := .Image -}}{{$platform}} {{index $image.Config.Labels "'"$ROR_REVISION_LABEL"'"}}{{"\n"}}{{- end -}}
{{- else -}}
{{.Image.OS}}/{{.Image.Architecture}}{{with .Image.Variant}}/{{.}}{{end}} {{index .Image.Config.Labels "'"$ROR_REVISION_LABEL"'"}}{{"\n"}}
{{- end -}}'

# Writes the description of the registry image $1 to the file $2. It reads the manifests, so nothing
# is pulled.
write_image_description() {
  docker buildx imagetools inspect "$1" --format "$ROR_IMAGE_DESCRIPTION_TEMPLATE" > "$2"
}

# Reads the registry image $1 into the description file $2. The --retry-if function $3 decides which
# failure gets another attempt. The registry messages go to the log, not into the file. The status is
# the status of the last read.
read_registry_image() {
  retry_with_backoff --retry-if "$3" write_image_description "$1" "$2" >&2
}

# The platforms of the description file $1, one per line. Fails when the file holds no platform line.
described_platforms() {
  local platforms
  platforms=$(awk '$1 ~ /^[^\/]+\/[^\/]+/ { print $1 }' "$1")
  [ -n "$platforms" ] && printf '%s\n' "$platforms"
}

# 0 when the description file $1 holds every platform in the comma-separated list $2, 1 when it lacks
# one or the list is empty, 2 when it holds no description. A wanted linux/arm64 matches an entry
# linux/arm64/v8.
image_has_platforms() {
  local present wanted platform line found
  present=$(described_platforms "$1") || return 2
  [ -n "$(printf '%s' "$2" | tr -d ',[:space:]')" ] || return 1
  IFS=',' read -r -a wanted <<< "$2"
  for platform in "${wanted[@]}"; do
    [ -n "$platform" ] || continue
    found=false
    while IFS= read -r line; do
      case $line in
        "$platform" | "$platform"/*) found=true ;;
      esac
    done <<< "$present"
    [ "$found" = true ] || return 1
  done
}

# 0 when every platform in the description file $1 has the revision label $2, 1 when one has no label
# or another revision, 2 when the file holds no description.
image_has_revision() {
  local revisions
  described_platforms "$1" > /dev/null || return 2
  revisions=$(awk '$1 ~ /^[^\/]+\/[^\/]+/ { print ($2 == "" ? "-" : $2) }' "$1" | sort -u)
  [ "$revisions" = "$2" ]
}

# True when the registry image $1 holds the commit $2 for every platform in the comma-separated list
# $3. Any failure, also a registry that does not answer, is false: then the caller builds.
dev_image_is_current() (
  local description
  description=$(mktemp) || exit 1
  trap 'rm -f "$description"' EXIT
  read_registry_image "$1" "$description" is_docker_registry_error &&
    image_has_platforms "$description" "$3" &&
    image_has_revision "$description" "$2"
)

# Checks that the registry image $1 holds the commit $2 for every platform in the comma-separated list
# $3, and logs the cause when it does not. 0: it does. 7: the registry gave no answer. 8: another
# revision, or a platform is missing. 2: the answer holds no description of the image.
check_published_dev_image() (
  local description status=0
  description=$(mktemp) || exit 1
  trap 'rm -f "$description"' EXIT
  if ! read_registry_image "$1" "$description" is_docker_registry_error_or_not_visible_yet; then
    ci_log "Cannot read $1 from the registry, so this run cannot check that it holds commit $2."
    exit 7
  fi
  image_has_revision "$description" "$2" || status=$?
  case $status in
    0) ;;
    1) ci_log "$1 holds another revision, not commit $2 (label $ROR_REVISION_LABEL)."; exit 8 ;;
    *) ci_log "The registry answer for $1 holds no description of the image."; exit 2 ;;
  esac
  image_has_platforms "$description" "$3" || status=$?
  case $status in
    0) ;;
    1) ci_log "$1 does not hold every platform of $3."; exit 8 ;;
    *) ci_log "The registry answer for $1 holds no description of the image."; exit 2 ;;
  esac
)

# Prints the platforms that the build for ES version $1 produces when it is asked for the
# comma-separated list $2. Gradle owns the answer, because a module can fix its platforms.
es_build_platforms() {
  local file="$ROR_REPO_ROOT/build/docker-platforms.txt"
  rm -f "$file"
  if ! (cd "$ROR_REPO_ROOT" && ./gradlew -q printEsDockerPlatforms "-PesVersion=$1" "-PdockerPlatforms=$2" </dev/null >&2); then
    ci_log "Cannot read from Gradle the platforms of the build for ES $1."
    return 1
  fi
  local platforms
  if ! platforms=$(tr -d '[:space:]' < "$file" 2>/dev/null) || [ -z "$platforms" ]; then
    ci_log "Gradle wrote no platforms for the build of ES $1 to $file."
    return 1
  fi
  printf '%s\n' "$platforms"
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
# A caller can capture the output: only the command writes to standard output.
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
    ci_log "retry_with_backoff needs a command."
    return 2
  fi
  if ! [[ $attempts =~ ^[0-9]+$ ]] || [ "$attempts" -lt 1 ]; then
    ci_log "ROR_RETRY_ATTEMPTS must be a positive integer. Its value is '$attempts'."
    return 2
  fi
  if ! [[ $delay =~ ^[0-9]+$ ]]; then
    ci_log "ROR_RETRY_DELAY_SECONDS must be a non-negative integer. Its value is '$delay'."
    return 2
  fi
  if [ -n "$retry_if" ] && ! declare -F "$retry_if" >/dev/null; then
    ci_log "--retry-if needs the name of a function. Its value is '$retry_if'."
    return 2
  fi

  log=""
  if [ -n "$retry_if" ]; then
    log=$(mktemp) || return 2
  fi

  while true; do
    # Both branches run inside a group followed by `|| true`, so errexit in a caller cannot stop us
    # at a failure we are about to repeat. The status is read inside the group, where PIPESTATUS
    # still holds the command's own value.
    if [ -n "$log" ]; then
      # tee keeps the output on the console. PIPESTATUS holds the status of the command itself, not
      # the status of tee.
      { "$@" 2>&1 | tee "$log"; status=${PIPESTATUS[0]}; } || true
    else
      { "$@"; status=$?; } || true
    fi

    if [ "$status" -eq 0 ]; then
      [ -n "$log" ] && rm -f "$log"
      return 0
    fi
    if [ -n "$retry_if" ] && ! "$retry_if" "$log" "$status"; then
      ci_log "'$1' failed. Another attempt gives the same result."
      rm -f "$log"
      return "$status"
    fi
    if [ "$attempt" -ge "$attempts" ]; then
      ci_log "'$1' failed on all $attempts attempts."
      [ -n "$log" ] && rm -f "$log"
      return "$status"
    fi
    ci_log "'$1' failed (attempt $attempt of $attempts). Next attempt in ${delay}s."
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
    'toomanyrequests|429 Too Many Requests|received unexpected HTTP status: 5[0-9][0-9]|unexpected status: 5[0-9][0-9]|50[0234] (Internal Server Error|Bad Gateway|Service Unavailable|Gateway Time-?out)|TLS handshake timeout|i/o timeout|connection reset by peer|unexpected EOF|net/http: request canceled' \
    "$1"
}

# A --retry-if function for a read right after a push: the registry can show a new tag some seconds
# later.
is_docker_registry_error_or_not_visible_yet() {
  is_docker_registry_error "$1" || grep -Eqi 'not found|manifest unknown' "$1"
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
    ci_log "Usage: retag_dev_image <source tag> <target tag>"
    return 1
  fi

  local SOURCE_TAG=$1
  local TARGET_TAG=$2
  ci_log "Tagging ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG} as ${ES_DEV_IMAGE_REPO}:${TARGET_TAG}."
  docker buildx imagetools create \
    -t "${ES_DEV_IMAGE_REPO}:${TARGET_TAG}" \
    "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}"
}

# Build & publish the ROR ES pre-build Docker image for the given ES version.
#
# To avoid rebuilding when the sources have not changed, every build is frozen under its commit tag
# <esVersion>-ror-<gitShortSha>. Before building we read that tag from the registry: when it holds every
# platform of this build, and its revision label names this commit, the Gradle build (the expensive
# build+push) is skipped. A commit tag without the label, or with another revision, is not trusted.
# A registry that does not answer also means a build. Setting FORCE_REBUILD=true bypasses the skip.
#
# Tags produced:
#   - <esVersion>-ror-<pluginVersion>   the version tag. Every branch writes it, so a build with
#                                       PUBLISH_VERSION_TAG=false leaves it out. A skip behaves like a
#                                       build: a build of this commit writes the version tag, so a skip
#                                       makes it from the commit tag.
#   - <esVersion>-ror-<gitShortSha>     the commit tag, pushed by the same Gradle build. The short SHA is
#                                       the first 12 characters of the commit: `git rev-parse --short`
#                                       gives a minimum length, which differs between a shallow clone
#                                       and a full one.
#   - <esVersion>-ror-<imageTag>        optional alias to the commit tag, when an image tag arg is given
#
# The image gets the label com.readonlyrest.revision=<full commit>. After the tags, the published tag,
# and the version tag when the run writes it, are read again: another revision or a missing platform
# returns 8, a registry that does not answer returns 7, and an answer without an image description
# returns 9.
#
# The build is for the standard platforms, linux/amd64 and linux/arm64. A comma-separated list in
# ROR_CUSTOM_DOCKER_PLATFORMS replaces them. A module can fix its platforms, and the checks expect
# the platforms that the build produces.
publish_ror_es_prebuild_plugin() {
  if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    ci_log "Usage: publish_ror_es_prebuild_plugin <ES version> [image tag]"
    return 1
  fi

  local ES_VERSION=$1
  local IMAGE_TAG="${2:-}"

  if ! [[ $ES_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    ci_log "'$ES_VERSION' is not an ES version. The format is X.Y.Z."
    return 2
  fi

  if ! docker info >/dev/null 2>&1; then
    ci_log "The docker daemon does not answer."
    return 3
  fi

  local ROR_VERSION GIT_SHA GIT_REVISION
  ROR_VERSION=$(gradle_property pluginVersion) || return 1
  # The commit names the source of the image, so a missing one must stop the build with the cause.
  if ! GIT_REVISION=$(git rev-parse HEAD); then
    ci_log "Cannot read the commit of the checkout. The image tag would then name no source."
    return 1
  fi
  GIT_SHA=$(printf '%s' "$GIT_REVISION" | cut -c1-12)

  local SOURCE_TAG="${ES_VERSION}-ror-${GIT_SHA}"

  # Normalize workflow and shell inputs before comparing.
  local FORCE_REBUILD_NORM=${FORCE_REBUILD:-false}
  FORCE_REBUILD_NORM=${FORCE_REBUILD_NORM,,}
  local WRITE_VERSION_TAG=${PUBLISH_VERSION_TAG:-true}
  WRITE_VERSION_TAG=${WRITE_VERSION_TAG,,}
  local PLATFORMS BUILD_PLATFORMS
  PLATFORMS=$(printf '%s' "${ROR_CUSTOM_DOCKER_PLATFORMS:-}" | tr -d '[:space:]')
  PLATFORMS=${PLATFORMS:-linux/amd64,linux/arm64}
  BUILD_PLATFORMS=$(es_build_platforms "$ES_VERSION" "$PLATFORMS") || return 1

  ci_log "Publishing the ROR pre-build image for ES $ES_VERSION, platforms $BUILD_PLATFORMS (source ${ES_DEV_IMAGE_REPO}:${SOURCE_TAG})."

  local SKIPPED=false
  if [ "$FORCE_REBUILD_NORM" != "true" ] &&
     dev_image_is_current "${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}" "$GIT_REVISION" "$BUILD_PLATFORMS"; then
    ci_log "The sources did not change. The image for this commit holds $BUILD_PLATFORMS of $GIT_REVISION, so this run skips the build."
    SKIPPED=true
  else
    # A registry can answer 429 to the pull or the push. Only that failure is repeated.
    # One buildx push writes the tags and the label, so the commit tag always names this build's image.
    if ! retry_with_backoff --retry-if is_docker_registry_error \
         ./gradlew publishEsRorPreBuildDockerImage "-PesVersion=$ES_VERSION" \
         "-PdockerPlatforms=$BUILD_PLATFORMS" \
         "-PadditionalImageTag=${ES_DEV_IMAGE_REPO}:${SOURCE_TAG}" \
         "-PimageLabel=${ROR_REVISION_LABEL}=${GIT_REVISION}" \
         "-PwriteVersionTag=$WRITE_VERSION_TAG" </dev/null; then
      ci_log "Cannot publish the ROR pre-build image for ES $ES_VERSION."
      return 4
    fi
  fi

  # On BOTH paths (built or skipped), make the tags that the build did not write, then check that the
  # published tag holds this commit. The caller fetches the image by that tag, and the e2e jobs test it.
  local VERSION_TAG="${ES_VERSION}-ror-${ROR_VERSION}"
  if [ "$SKIPPED" = true ] && [ "$WRITE_VERSION_TAG" != "false" ] && ! retag_dev_image "$SOURCE_TAG" "$VERSION_TAG"; then
    ci_log "Cannot tag the ROR pre-build image as ${ES_DEV_IMAGE_REPO}:${VERSION_TAG}."
    return 6
  fi
  local PUBLISHED_TAG=$SOURCE_TAG
  if [ -n "$IMAGE_TAG" ]; then
    if ! retag_dev_image "$SOURCE_TAG" "${ES_VERSION}-ror-${IMAGE_TAG}"; then
      ci_log "Cannot tag the ROR pre-build image as ${ES_DEV_IMAGE_REPO}:${ES_VERSION}-ror-${IMAGE_TAG}."
      return 6
    fi
    PUBLISHED_TAG="${ES_VERSION}-ror-${IMAGE_TAG}"
  fi
  local CHECKED_TAGS=$PUBLISHED_TAG CHECKED_TAG CHECK_STATUS
  [ "$WRITE_VERSION_TAG" != "false" ] && CHECKED_TAGS="$CHECKED_TAGS $VERSION_TAG"
  for CHECKED_TAG in $CHECKED_TAGS; do
    CHECK_STATUS=0
    check_published_dev_image "${ES_DEV_IMAGE_REPO}:${CHECKED_TAG}" "$GIT_REVISION" "$BUILD_PLATFORMS" || CHECK_STATUS=$?
    case $CHECK_STATUS in
      0) ;;
      7 | 8) return "$CHECK_STATUS" ;;
      *) return 9 ;;
    esac
  done
}

# The tag of one published version, from a ROR version $1 and an ES version $2. The release docs
# quote this shape, so a change here needs a change there.
release_tag() {
  printf 'v%s_es%s\n' "$1" "$2"
}

# Prints, one per line, every tag of origin whose name matches the refs/tags glob $1. Prints nothing
# when none matches, and fails when it cannot ask. Ask only origin: a local tag survives an attempt
# that tagged and then failed to push.
#
# No variable holds the answer: under `bash -x`, a variable that holds hundreds of tags appears in
# full on every line that carries it.
origin_tags() {
  local glob=$1 status
  retry_with_backoff git ls-remote --tags origin "refs/tags/${glob}" \
    | awk '{print $2}' | sed -e 's#^refs/tags/##' -e 's/\^{}$//' | sort -u
  status=${PIPESTATUS[0]}
  if [ "$status" -ne 0 ]; then
    ci_log "Cannot read the tags of origin for ${glob}."
    return "$status"
  fi
}

# The tag on origin is the record that a version is published. Prints `absent` or `present`, and
# fails only when it cannot ask: a query failure read as "absent" publishes the whole release again.
remote_tag_state() {
  local git_tag=$1 tags

  tags=$(origin_tags "$git_tag") || return 1
  if [ -n "$tags" ]; then
    printf 'present\n'
  else
    printf 'absent\n'
  fi
}

# Writes that record. The status of the push is the status of this function: a tag that stays local
# leaves the version published and unmarked, and the next run publishes it again.
tag() {
  GIT_TAG="$1"
  local state

  # The caller filtered the published versions before it built anything, so this tag was absent
  # minutes ago. It can be here only because somebody pushed it while this run worked, and this run
  # then published the version a second time. Say so: a push would fail here anyway, because the
  # annotated tag below is a new object, and origin holds another one under that name.
  state=$(remote_tag_state "$GIT_TAG") || return 1
  if [ "$state" = present ]; then
    ci_log "Git tag $GIT_TAG reached origin while this run worked, so this run published ES ${GIT_TAG#*_es} a second time."
    return 0
  fi

  ci_log "Tagging as $GIT_TAG."
  git config --global push.default matching
  git config --global user.email "support@readonlyrest.com"
  git config --global user.name "CI"
  # -f overwrites any stale local tag from a previous failed push attempt
  git tag -fa "$GIT_TAG" -m "Generated tag from CI build $CI_BUILD_NUMBER"
  retry_with_backoff git push origin "$GIT_TAG"
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
    ci_log "Cannot upload '$LOCAL_FILE'. It is not a file."
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

# Writes a disk report to standard error, where every message of this file goes. One stream for the
# whole report: df, du and docker write most of it, and a header must stay above its own section.
log_disk_usage() {
  local label="${1:-}"
  {
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
  } >&2
}
