#!/usr/bin/env bash
# Chooses where a job pulls the toolchains image from: mirror.gcr.io or Docker Hub.
#
# mirror.gcr.io is a pull-through cache for docker.io. It can serve an old image, so the script
# trusts it only when it serves the digest of the last push. TOOLCHAINS_DIGEST_FILE holds that
# digest. The script asks the mirror for the digest behind the tag and compares the two. It sends
# no request to Docker Hub.
#
# A mirrored name carries the digest, not the tag, because a tag can move between this check and
# the pull. A Docker Hub name keeps the tag: no digest is proven in that case.
#
# main() holds the steps. Docker Hub is the answer whenever the mirror cannot be trusted, so a
# wrong choice costs speed only.
#
# INPUT   TOOLCHAINS_IMAGE        repository:tag, no registry host
#         TOOLCHAINS_DIGEST_FILE  the digest of the last push. record_toolchains_digest caches it.
#                                 Default .toolchains-digest. An absent file means no digest.
#         ROR_DOCKER_HUB_MIRROR   false skips the mirror
# OUTPUT  image=<name>            the name to pull. Mirrored names end in @sha256:...
#         mirrored=true|false     false means a Docker Hub name, which needs a login
#         Both go to $GITHUB_OUTPUT when it is set, and to stdout otherwise.
#
# The script always exits 0. A mirror is an optimisation. It must never stop a run.
#
# For the reason this script exists, see "The container: image" in ci/CI.md.
set -uo pipefail

MIRROR_HOST="mirror.gcr.io"

main() {
  local image="${TOOLCHAINS_IMAGE:-}"
  local pushed served

  [ -n "$image" ] || { echo "[CI] TOOLCHAINS_IMAGE is empty. Nothing to resolve." >&2; exit 0; }

  has_tag "$image" || use_docker_hub "$image" \
    "The image name has no tag: '${image}'. A digest check needs one."

  mirror_is_on || use_docker_hub "$image" \
    "ROR_DOCKER_HUB_MIRROR is false, so the mirror is off by request."

  pushed="$(recorded_digest)"
  [ -n "$pushed" ] || use_docker_hub "$image" \
    "No digest is on record for '${image}'." \
    "The 'Build toolchains image' job records the digest of every push. Run that job once."

  served="$(mirror_digest "$image")"
  [ -n "$served" ] || use_docker_hub "$image" \
    "${MIRROR_HOST} holds no image for '${image}'." \
    "The mirror copies an image only after a pull asks for it."

  if [ "$served" != "$pushed" ]; then
    # The annotation is the headline. The log lines below carry the two digests.
    warn "${MIRROR_HOST} has not caught up with the last push of ${image}. This run uses Docker Hub."
    use_docker_hub "$image" \
      "${MIRROR_HOST} holds an old image for '${image}'." \
      "It serves ${served}. The last push was ${pushed}."
  fi

  # The name carries the digest, not the tag. A tag can move between this check and the pull, and
  # the jobs of one run start hours apart.
  use_mirror "$image" "$served"
}

has_tag() {
  case "$1" in *:*) return 0 ;; *) return 1 ;; esac
}

mirror_is_on() {
  local flag
  flag="$(echo "${ROR_DOCKER_HUB_MIRROR:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
  [ "$flag" != "false" ]
}

# build_toolchains_image found this digest. The Actions cache carried it here.
recorded_digest() {
  local file="${TOOLCHAINS_DIGEST_FILE:-.toolchains-digest}"
  [ -r "$file" ] && tr -d '[:space:]' < "$file"
}

# One HEAD request. It downloads no image.
mirror_digest() {
  local repo="${1%:*}" tag="${1##*:}" accept
  accept='application/vnd.oci.image.index.v1+json'
  accept="${accept},application/vnd.docker.distribution.manifest.list.v2+json"
  accept="${accept},application/vnd.oci.image.manifest.v1+json"
  accept="${accept},application/vnd.docker.distribution.manifest.v2+json"

  curl -fsS --max-time 15 -o /dev/null -D - -I -H "Accept: ${accept}" \
       "https://${MIRROR_HOST}/v2/${repo}/manifests/${tag}" 2>/dev/null \
    | tr -d '\r' | awk 'tolower($1) == "docker-content-digest:" { print $2 }'
}

# $1 is the image. Every argument after it is one line of the reason. Each line says one fact: what
# the script looked for, and what it found. The last line says what the run does about it.
use_docker_hub() {
  local image="$1" line
  shift
  for line in "$@"; do echo "[CI] $line"; done
  echo "[CI] So this run pulls from Docker Hub. The pull works."
  echo "[CI] It is slower, and it counts against the Docker Hub rate limit."
  emit "$image" "false"
}

use_mirror() {
  echo "[CI] ${MIRROR_HOST} serves the digest of the last push: $2."
  echo "[CI] So this run pulls from the mirror."
  emit "${MIRROR_HOST}/${1%:*}@$2" "true"
}

emit() {
  echo "[CI] Toolchains image: $1 (mirrored=$2)"
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf 'image=%s\nmirrored=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  else
    printf 'image=%s\nmirrored=%s\n' "$1" "$2"
  fi
  exit 0
}

warn() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::$1"; else echo "[CI] WARNING: $1"; fi
}

main "$@"
