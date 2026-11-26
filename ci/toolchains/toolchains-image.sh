#!/usr/bin/env bash
# The only owner of the toolchains image tag rules and of the Docker Hub calls about them.
# Run it without arguments to see the subcommands.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=ci/log.sh
source ci/log.sh

# The JDK set and the Gradle version. A JDK or Gradle bump edits this line.
PREFIX=beshultd/ror-ci-toolchains:jdk-8-11-17-21-gradle-9.8.2
REPOSITORY=${PREFIX%:*}
DEPS_NAME_PREFIX="${PREFIX##*:}-deps-"
DEVELOP_TAG="${PREFIX}-develop"

usage() {
  cat >&2 <<USAGE
Usage: $0 <subcommand>
  deps-tag          print ${REPOSITORY}:${DEPS_NAME_PREFIX}<hash> for the build files of this checkout
  select            print image=, digest=, deps_tag= and deps_missing= for this checkout (no credentials)
  build [--fresh]   build and push the deps tag when it is missing, and move the develop tag when
                    HEAD is the head of develop. --fresh builds from fresh base images and an empty
                    Gradle home. Needs DOCKER_REGISTRY_USER and DOCKER_REGISTRY_PASSWORD.
  cleanup           delete the deps tags last pushed more than MAX_AGE_DAYS (30) ago, except the newest.
                    Needs DOCKER_HUB_USER and DOCKER_HUB_DELETE_TOKEN; without the token it lists only.
  is-develop-head   exit 0 when HEAD is the head of develop on origin
USAGE
  exit 1
}

# Each new hash costs an image build, so the hash reads only the files that decide the baked
# dependencies. Comment and blank lines do not count.
deps_tag() {
  local hash
  hash=$(find . \( -path ./.git -o -name build -o -name .gradle \) -prune -o -type f \
           \( -name '*.gradle' -o -path './es*/gradle.properties' -o -name gradle-wrapper.properties \
              -o -path './build-base/src/main/*' -o -name 'JdkToolchains.Dockerfile*' \) -print0 |
         LC_ALL=C sort -z |
         while IFS= read -r -d '' f; do
           printf '%s\n' "$f"
           grep -v -E '^[[:space:]]*(#|//|$)' "$f" || true
         done | sha256sum | cut -c1-16)
  echo "${REPOSITORY}:${DEPS_NAME_PREFIX}${hash}"
}

# Prints the digest of <repository>:<tag>, or nothing when the tag does not exist.
# Fails when Docker Hub cannot answer. A HEAD request does not count against the pull limit.
registry_digest() {
  local ref=$1 repo tag token headers
  repo=${ref%:*} tag=${ref##*:}
  token=$(curl -fsS --max-time 20 \
    "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull" | jq -r '.token') || return 1
  headers=$(curl -sS --max-time 20 -I -o /dev/stdout -w '%{http_code}\n' \
    -H "Authorization: Bearer $token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "https://registry-1.docker.io/v2/${repo}/manifests/${tag}") || return 1
  case "$(tail -1 <<<"$headers")" in
    200) tr -d '\r' <<<"$headers" | sed -n 's/^docker-content-digest: *//Ip' | head -1 ;;
    404) ;;
    *) return 1 ;;
  esac
}

# Only the head of develop may move the develop tag. The rule is here, not in a workflow line,
# because a pull request runs its own copy of the workflow files.
is_develop_head() {
  local develop_head
  develop_head=$(git ls-remote origin refs/heads/develop | cut -f1)
  [ -n "$develop_head" ] && [ "$develop_head" = "$(git rev-parse HEAD)" ]
}

# The deps tag of this checkout, or the develop tag when the deps tag is missing: Gradle then downloads
# only the changed jars.
select_image() {
  local deps image missing=false digest
  deps=$(deps_tag)
  image=$deps
  digest=$(registry_digest "$image") || { ci_log "Docker Hub cannot say whether $image exists."; return 1; }
  if [ -z "$digest" ]; then
    image=$DEVELOP_TAG missing=true
    ci_log "No image $deps. This run takes $image."
    digest=$(registry_digest "$image") || { ci_log "Docker Hub cannot say whether $image exists."; return 1; }
    [ -n "$digest" ] || { ci_log "No image $image."; return 1; }
  fi
  echo "image=$image"
  echo "digest=$digest"
  echo "deps_tag=$deps"
  echo "deps_missing=$missing"
}

build() {
  local fresh=false arg deps digest log
  local -a build_args
  for arg in "$@"; do
    case "$arg" in
      --fresh) fresh=true ;;
      *) usage ;;
    esac
  done
  deps=$(deps_tag)

  # shellcheck source=ci/configure-docker.sh
  source ci/configure-docker.sh >&2

  digest=$(registry_digest "$deps") || { ci_log "Docker Hub cannot say whether $deps exists."; return 1; }
  if [ -z "$digest" ] || [ "$fresh" = true ]; then
    if [ "$fresh" = true ]; then
      build_args=(--pull --no-cache)
    else
      build_args=(--cache-from "$DEVELOP_TAG" --build-arg GRADLE_HOME_SEED="$DEVELOP_TAG")
    fi
    ci_log "Building $deps."
    # BUILDKIT_INLINE_CACHE puts the cache metadata in the image, so the next build can reuse its layers.
    docker build "${build_args[@]}" --build-arg BUILDKIT_INLINE_CACHE=1 \
      --build-arg MIRROR="${ROR_DOCKER_HUB_MIRROR_PREFIX:-}" \
      -f ci/toolchains/JdkToolchains.Dockerfile -t "$deps" . >&2
    log=$(docker push "$deps" 2>&1 | tee /dev/stderr)
    digest=$(sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' <<<"$log" | head -1)
    [ -n "$digest" ] || { ci_log "The push of $deps printed no digest."; return 1; }
  else
    ci_log "$deps exists."
  fi

  if is_develop_head && [ "$(registry_digest "$DEVELOP_TAG")" != "$digest" ]; then
    ci_log "Pointing $DEVELOP_TAG at $deps."
    docker buildx imagetools create -t "$DEVELOP_TAG" "${deps%:*}@$digest" >&2
  fi
  echo "$digest"
}

# NOW_EPOCH overrides the clock (tests). It never deletes the develop tag or the newest deps tag.
cleanup() {
  local api cutoff tags old jwt code t status=0
  api="https://hub.docker.com/v2/namespaces/${REPOSITORY%%/*}/repositories/${REPOSITORY#*/}/tags"
  cutoff=$(( ${NOW_EPOCH:-$(date -u +%s)} - ${MAX_AGE_DAYS:-30} * 86400 ))

  tags=$(curl -fsS "${api}?page_size=100" | jq -c '.results') # one page: the cleanup keeps the count low
  old=$(jq -r --arg p "$DEPS_NAME_PREFIX" --argjson cutoff "$cutoff" '
    [.[] | select(.name | startswith($p)) | {name, t: (.tag_last_pushed | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)}]
    | sort_by(.t) | .[:-1] | .[] | select(.t < $cutoff) | .name' <<<"$tags")

  [ -n "$old" ] || { ci_log "No toolchains image tag to delete."; return 0; }
  if [ -z "${DOCKER_HUB_DELETE_TOKEN:-}" ]; then
    ci_log "No DOCKER_HUB_DELETE_TOKEN. These tags would go:"; echo "$old"; return 0
  fi
  jwt=$(jq -n --arg u "${DOCKER_HUB_USER:?}" --arg p "$DOCKER_HUB_DELETE_TOKEN" '{username: $u, password: $p}' |
        curl -fsS -H 'Content-Type: application/json' -d @- https://hub.docker.com/v2/users/login | jq -r '.token')
  echo "::add-mask::$jwt"
  while read -r t; do
    code=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $jwt" "$api/$t")
    if [ "$code" = 204 ] || [ "$code" = 200 ]; then ci_log "Deleted $t."; else ci_log "Cannot delete $t: HTTP $code."; status=1; fi
  done <<<"$old"
  return "$status"
}

subcommand=${1:-}
[ "$#" -eq 0 ] || shift
case "$subcommand" in
  deps-tag) deps_tag ;;
  select) select_image ;;
  build) build "$@" ;;
  cleanup) cleanup ;;
  is-develop-head) is_develop_head ;;
  *) usage ;;
esac
