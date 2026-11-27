#!/usr/bin/env bash
# The only owner of the toolchains image tag rules and of the registry calls about them.
# Run it without arguments to see the subcommands.
set -euo pipefail
cd "$(dirname "$0")/../.."
# shellcheck source=ci/log.sh
source ci/log.sh

# The JDK set and the Gradle version. A JDK or Gradle bump edits this line.
PREFIX=beshultd/ror-ci-toolchains:jdk-8-11-17-21-gradle-9.8.2
REPOSITORY=${PREFIX%:*}
COMMIT_TAG_PREFIX="${PREFIX##*:}-commit-"
DEVELOP_TAG="${PREFIX}-develop"
MIRROR_HOST="mirror.gcr.io"
MANIFEST_ACCEPT='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'

usage() {
  cat >&2 <<USAGE
Usage: $0 <subcommand>
  commit-tag              print ${REPOSITORY}:${COMMIT_TAG_PREFIX}<hash> for the build files of this checkout
  select                  pick the commit or develop image, choose mirror or Docker Hub, and emit
                          image= and commit_image_missing= (to \$GITHUB_OUTPUT when set, else stdout)
  build [--full-rebuild]  build and push the commit tag when it is missing, and move the develop tag when
                          HEAD is the head of develop. --full-rebuild builds from new base images and an
                          empty Gradle home. Needs DOCKER_REGISTRY_USER and DOCKER_REGISTRY_PASSWORD.
  cleanup                 delete commit tags last pushed more than MAX_AGE_DAYS (30) ago, except the newest.
                          Needs DOCKER_HUB_USER and DOCKER_HUB_DELETE_TOKEN; without the token it lists only.
  is-develop-head         exit 0 when HEAD is the head of develop on origin
USAGE
  exit 1
}

# Each new hash costs an image build, so the hash reads only the files that decide the baked
# dependencies. Comment and blank lines do not count.
commit_tag() {
  local hash
  hash=$(find . \( -path ./.git -o -name build -o -name .gradle \) -prune -o -type f \
           \( -name '*.gradle' -o -path './es*/gradle.properties' -o -name gradle-wrapper.properties \
              -o -path './build-base/src/main/*' -o -name 'JdkToolchains.Dockerfile*' \) -print0 |
         LC_ALL=C sort -z |
         while IFS= read -r -d '' f; do
           printf '%s\n' "$f"
           grep -v -E '^[[:space:]]*(#|//|$)' "$f" || true
         done | sha256sum | cut -c1-16)
  echo "${REPOSITORY}:${COMMIT_TAG_PREFIX}${hash}"
}

# Sends one HEAD request for a manifest on <host>. Prints response headers followed by the HTTP code.
# A HEAD request downloads no image and does not count against the Docker Hub pull limit.
manifest_head() {
  local host=$1 repo=$2 ref=$3 token=${4:-}
  local -a auth=()
  [ -z "$token" ] || auth=(-H "Authorization: Bearer $token")
  curl -sS --max-time 20 -I -o /dev/stdout -w '%{http_code}\n' \
    "${auth[@]}" -H "Accept: ${MANIFEST_ACCEPT}" \
    "https://${host}/v2/${repo}/manifests/${ref}"
}

# Prints the digest of <repository>:<tag> on Docker Hub, or nothing when the tag does not exist.
# Fails when Docker Hub cannot answer.
registry_digest() {
  local ref=$1 repo tag token headers
  repo=${ref%:*} tag=${ref##*:}
  token=$(curl -fsS --max-time 20 \
    "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull" | jq -r '.token') || return 1
  headers=$(manifest_head registry-1.docker.io "$repo" "$tag" "$token") || return 1
  case "$(tail -1 <<<"$headers")" in
    200) tr -d '\r' <<<"$headers" | sed -n 's/^docker-content-digest: *//Ip' | head -1 ;;
    404) ;;
    *) return 1 ;;
  esac
}

mirror_is_on() {
  local flag
  flag=$(echo "${ROR_DOCKER_HUB_MIRROR:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
  [ "$flag" != "false" ]
}

# Asks the pull-through cache by digest, never by tag: a digest names the exact bytes.
mirror_has_digest() {
  local digest=$1 headers
  headers=$(manifest_head "$MIRROR_HOST" "$REPOSITORY" "$digest") || return 1
  [ "$(tail -1 <<<"$headers")" = "200" ]
}

# Only the head of develop may move the develop tag. The rule is here, not in a workflow line,
# because a pull request runs its own copy of the workflow files.
is_develop_head() {
  local develop_head
  develop_head=$(git ls-remote origin refs/heads/develop | cut -f1)
  [ -n "$develop_head" ] && [ "$develop_head" = "$(git rev-parse HEAD)" ]
}

summarise_selection() {
  local ref=$1 mirrored=$2 reason=${3:-} line
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  if [ "$mirrored" = "true" ]; then
    line="This run pulls the toolchains image from ${MIRROR_HOST}."
  else
    line="This run pulls the toolchains image from Docker Hub: ${reason}"
  fi
  printf '### Toolchains image\n\n%s\n\n`%s`\n' "$line" "$ref" >> "$GITHUB_STEP_SUMMARY"
}

# Selects the commit image, or the develop image when the commit image is missing, then chooses
# mirror.gcr.io by digest or Docker Hub by tag. A mirror fault falls back to Docker Hub.
select_image() {
  local commit_img image missing=false digest pull_ref mirrored=false reason=""
  commit_img=$(commit_tag)
  image=$commit_img
  digest=$(registry_digest "$image") || { ci_log "Docker Hub cannot say whether $image exists."; return 1; }
  if [ -z "$digest" ]; then
    image=$DEVELOP_TAG missing=true
    ci_log "No image $commit_img. This run takes $image."
    digest=$(registry_digest "$image") || { ci_log "Docker Hub cannot say whether $image exists."; return 1; }
    [ -n "$digest" ] || { ci_log "No image $image."; return 1; }
  fi

  if ! mirror_is_on; then
    reason="ROR_DOCKER_HUB_MIRROR is false."
    ci_log "This run skips the mirror: $reason"
    pull_ref=$image
  elif ! mirror_has_digest "$digest"; then
    reason="${MIRROR_HOST} cannot serve ${digest}."
    ci_log "This run skips the mirror: $reason"
    pull_ref=$image
  else
    mirrored=true
    pull_ref="${MIRROR_HOST}/${REPOSITORY}@${digest}"
  fi

  ci_log "Toolchains image: $pull_ref"
  summarise_selection "$pull_ref" "$mirrored" "$reason"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
      printf 'image=%s\n' "$pull_ref"
      printf 'commit_image_missing=%s\n' "$missing"
    } >> "$GITHUB_OUTPUT"
  else
    printf 'image=%s\n' "$pull_ref"
    printf 'commit_image_missing=%s\n' "$missing"
  fi
}

build() {
  local full_rebuild=false arg commit_img digest log
  local -a build_args
  for arg in "$@"; do
    case "$arg" in
      --full-rebuild) full_rebuild=true ;;
      *) usage ;;
    esac
  done
  commit_img=$(commit_tag)

  # shellcheck source=ci/configure-docker.sh
  source ci/configure-docker.sh >&2

  digest=$(registry_digest "$commit_img") || { ci_log "Docker Hub cannot say whether $commit_img exists."; return 1; }
  if [ -z "$digest" ] || [ "$full_rebuild" = true ]; then
    if [ "$full_rebuild" = true ]; then
      build_args=(--pull --no-cache)
    else
      build_args=(--cache-from "$DEVELOP_TAG" --build-arg GRADLE_HOME_SEED="$DEVELOP_TAG")
    fi
    ci_log "Building $commit_img."
    # BUILDKIT_INLINE_CACHE puts the cache metadata in the image, so the next build can reuse its layers.
    docker build "${build_args[@]}" --build-arg BUILDKIT_INLINE_CACHE=1 \
      --build-arg MIRROR="${ROR_DOCKER_HUB_MIRROR_PREFIX:-}" \
      -f ci/toolchains/JdkToolchains.Dockerfile -t "$commit_img" . >&2
    log=$(docker push "$commit_img" 2>&1 | tee /dev/stderr)
    digest=$(sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' <<<"$log" | head -1)
    [ -n "$digest" ] || { ci_log "The push of $commit_img printed no digest."; return 1; }
  else
    ci_log "$commit_img exists."
  fi

  if is_develop_head && [ "$(registry_digest "$DEVELOP_TAG")" != "$digest" ]; then
    ci_log "Pointing $DEVELOP_TAG at $commit_img."
    docker buildx imagetools create -t "$DEVELOP_TAG" "${commit_img%:*}@$digest" >&2
  fi
  echo "$digest"
}

# NOW_EPOCH overrides the clock (tests). It never deletes the develop tag or the newest commit tag.
cleanup() {
  local api cutoff tags old jwt code t status=0
  api="https://hub.docker.com/v2/namespaces/${REPOSITORY%%/*}/repositories/${REPOSITORY#*/}/tags"
  cutoff=$(( ${NOW_EPOCH:-$(date -u +%s)} - ${MAX_AGE_DAYS:-30} * 86400 ))

  tags=$(curl -fsS "${api}?page_size=100" | jq -c '.results') # one page: the cleanup keeps the count low
  old=$(jq -r --arg p "$COMMIT_TAG_PREFIX" --argjson cutoff "$cutoff" '
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
  commit-tag) commit_tag ;;
  select) select_image ;;
  build) build "$@" ;;
  cleanup) cleanup ;;
  is-develop-head) is_develop_head ;;
  *) usage ;;
esac
