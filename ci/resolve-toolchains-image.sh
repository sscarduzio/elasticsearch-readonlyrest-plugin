#!/usr/bin/env bash
# Chooses where a job pulls the toolchains image from: mirror.gcr.io or Docker Hub.
#
# mirror.gcr.io is a pull-through cache for docker.io. A job pulls by digest, and a digest names the
# bytes. A cache cannot answer a digest with the wrong image. So the script asks one question: can
# the mirror serve this digest? TOOLCHAINS_DIGEST_FILE holds the digest of the last push. No request
# goes to Docker Hub.
#
# A mirrored name carries the digest, not the tag, because a tag can move between this check and
# the pull. A Docker Hub name keeps the tag: no digest is proven in that case.
#
# main() holds the steps. Docker Hub is the answer when the mirror cannot serve the digest, so a
# wrong choice costs speed only.
#
# INPUT   TOOLCHAINS_IMAGE        repository:tag, no registry host. See ci/toolchains/image.env
#         TOOLCHAINS_DIGEST_FILE  the digest of the last push. record_digest caches it.
#                                 Default .toolchains-digest. An absent file means no digest.
#         ROR_DOCKER_HUB_MIRROR   false skips the mirror
# OUTPUT  image=<name>            the name to pull. Mirrored names end in @sha256:...
#         mirrored=true|false     false means a Docker Hub name, which needs a login
#         Both go to $GITHUB_OUTPUT when it is set, and to stdout otherwise.
#         The choice also goes to $GITHUB_STEP_SUMMARY, so the run page shows it.
#
# The script always exits 0. A mirror is an optimisation. It must never stop a run.
#
# For the reason this script exists, see "The container: image" in ci/CI.md.
set -uo pipefail

MIRROR_HOST="mirror.gcr.io"

main() {
  local image="${TOOLCHAINS_IMAGE:-}"
  local pushed

  [ -n "$image" ] || { echo "[CI] TOOLCHAINS_IMAGE is empty. Nothing to resolve." >&2; exit 0; }

  has_tag "$image" || use_docker_hub "$image" "the image name has no tag."
  mirror_is_on     || use_docker_hub "$image" "ROR_DOCKER_HUB_MIRROR is false."

  pushed="$(recorded_digest)"
  [ -n "$pushed" ] || use_docker_hub "$image" \
    "no digest recorded for this tag. Run 'Build toolchains image'."

  mirror_has_digest "$image" "$pushed" || use_docker_hub "$image" \
    "${MIRROR_HOST} cannot serve ${pushed}."

  # The name carries the digest, not the tag. A tag can move between this check and the pull, and
  # the jobs of one run start hours apart.
  use_mirror "$image" "$pushed"
}

has_tag() {
  case "$1" in *:*) return 0 ;; *) return 1 ;; esac
}

mirror_is_on() {
  local flag
  flag="$(echo "${ROR_DOCKER_HUB_MIRROR:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  [ "$flag" != "false" ]
}

# build-toolchains-image.yml found this digest. The Actions cache carried it here.
recorded_digest() {
  local file="${TOOLCHAINS_DIGEST_FILE:-.toolchains-digest}"
  [ -r "$file" ] && tr -d '[:space:]' < "$file"
}

# One HEAD request. It downloads no image. The mirror fetches a digest it has never held, so this
# passes in the hours after a rebuild, before the mirror knows the new tag.
mirror_has_digest() {
  local repo="${1%:*}" digest="$2" accept
  accept='application/vnd.oci.image.index.v1+json'
  accept="${accept},application/vnd.docker.distribution.manifest.list.v2+json"
  accept="${accept},application/vnd.oci.image.manifest.v1+json"
  accept="${accept},application/vnd.docker.distribution.manifest.v2+json"

  curl -fsS --max-time 15 -o /dev/null -I -H "Accept: ${accept}" \
       "https://${MIRROR_HOST}/v2/${repo}/manifests/${digest}" >/dev/null 2>&1
}

# $2 is the reason, in one short sentence. It names the fact, not the decision.
use_docker_hub() {
  echo "[CI] This run skips the mirror: $2"
  emit "$1" "false" "$2"
}

use_mirror() {
  emit "${MIRROR_HOST}/${1%:*}@$2" "true"
}

# The run page carries the choice. A step log hides it, and a run that left the mirror in silence is
# the fault this note makes visible.
summarise() {
  local line
  [ -n "${GITHUB_STEP_SUMMARY:-}" ] || return 0
  if [ "$2" = "true" ]; then
    line="This run pulls the toolchains image from ${MIRROR_HOST}."
  else
    line="This run pulls the toolchains image from Docker Hub: $3"
  fi
  printf '### Toolchains image\n\n%s\n\n`%s`\n' "$line" "$1" >> "$GITHUB_STEP_SUMMARY"
}

emit() {
  echo "[CI] Toolchains image: $1"
  summarise "$1" "$2" "${3:-}"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'image=%s\nmirrored=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  else
    printf 'image=%s\nmirrored=%s\n' "$1" "$2"
  fi
  exit 0
}

main "$@"
