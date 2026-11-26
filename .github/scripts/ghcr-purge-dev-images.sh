#!/bin/bash
# Deletes old ROR dev images from one ghcr.io container package.
#
# Rule: per ES/KBN version (the tag part before "-ror-"), keep the KEEP newest images and delete
# an older one only when it is older than MIN_AGE_DAYS. An untagged manifest is deleted only when it
# is older than MIN_AGE_DAYS and no kept image references it (multi-platform children, attestations).
#
# Usage: ci/ghcr-purge-dev-images.sh <package name>
# Env:   GH_TOKEN (packages read/delete), PURGE_OWNER (the package owner), PURGE_OWNER_KIND
#        (users|orgs, default orgs), KEEP (2), MIN_AGE_DAYS (7), MAX_DELETES (300),
#        DRY_RUN (true unless exactly "false").
# Returns: 1 usage, 2 cannot list versions, 3 cannot read a kept manifest (nothing deleted), 4 a delete failed.
set -uo pipefail

PACKAGE=${1:-}
if [ "$#" -ne 1 ] || [ -z "$PACKAGE" ]; then
  echo "Usage: $0 <package name>" >&2
  exit 1
fi

OWNER=${PURGE_OWNER:?PURGE_OWNER is not set}
OWNER_KIND=${PURGE_OWNER_KIND:-orgs}
KEEP=${KEEP:-2}
MIN_AGE_DAYS=${MIN_AGE_DAYS:-7}
MAX_DELETES=${MAX_DELETES:-300}
DRY_RUN=$([ "${DRY_RUN:-true}" = "false" ] && echo false || echo true)
NOW_EPOCH=${PURGE_NOW_EPOCH:-$(date -u +%s)}
API_BASE="/${OWNER_KIND}/${OWNER}/packages/container/${PACKAGE}/versions"

list_versions() {
  gh api --paginate "${API_BASE}?per_page=100" | jq -s 'add // []'
}

# Prints the digests that the manifest $1 references, one per line. An image manifest references none.
manifest_children() {
  local digest=$1 token
  token=$(curl -fsS -u "token:${GH_TOKEN:?}" \
    "https://ghcr.io/token?service=ghcr.io&scope=repository:${OWNER}/${PACKAGE}:pull" | jq -r '.token') || return 1
  curl -fsS -H "Authorization: Bearer $token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "https://ghcr.io/v2/${OWNER}/${PACKAGE}/manifests/${digest}" | jq -r '.manifests[]?.digest'
}

delete_version() {
  gh api --method DELETE "${API_BASE}/$1" >/dev/null
}

# Plan as JSON: {delete_tagged: [...], kept_tagged: [...], untagged_old: [...]}; each item {id, name, tags}.
plan() {
  jq --argjson keep "$KEEP" --argjson cutoff "$((NOW_EPOCH - MIN_AGE_DAYS * 86400))" '
    def tags: (.metadata.container.tags // []);
    def age_ok: (.updated_at | fromdateiso8601) < $cutoff;
    def group_key: (tags | map(split("-ror-")[0]) | unique) as $k | if ($k | length) == 1 then $k[0] else null end;
    def item: {id, name, tags: tags};
    (map(select((tags | length) > 0))) as $tagged
    | ($tagged | map(select(group_key == null))) as $mixed
    | ($tagged | map(select(group_key != null)) | group_by(group_key)
        | map(sort_by(.updated_at) | reverse | to_entries)) as $groups
    | ($groups | map(.[] | select(.key >= $keep and (.value | age_ok)) | .value)) as $del
    | {
        delete_tagged: ($del | map(item)),
        kept_tagged: (($groups | map(.[] | .value) | map(select(. as $v | $del | index($v) | not))) + $mixed | map(item)),
        untagged_old: (map(select((tags | length) == 0 and age_ok)) | map(item))
      }'
}

VERSIONS=$(list_versions) || { echo "Cannot list the versions of ${OWNER}/${PACKAGE}." >&2; exit 2; }
PLAN=$(printf '%s' "$VERSIONS" | plan) || exit 2

# The untagged step needs every child of every kept image. One unreadable manifest stops the run.
REFERENCED=""
while read -r digest; do
  [ -n "$digest" ] || continue
  if ! children=$(manifest_children "$digest"); then
    echo "Cannot read the manifest ${OWNER}/${PACKAGE}@${digest}. Nothing is deleted." >&2
    exit 3
  fi
  REFERENCED+="${children}"$'\n'
done < <(printf '%s' "$PLAN" | jq -r '.kept_tagged[].name')

UNTAGGED=$(printf '%s' "$PLAN" | jq -c --arg refs "$REFERENCED" \
  '($refs | split("\n") | map(select(. != ""))) as $r | .untagged_old | map(select(.name as $n | $r | index($n) | not))')

echo "${OWNER}/${PACKAGE}: $(printf '%s' "$VERSIONS" | jq length) versions, keep ${KEEP} per version, min age ${MIN_AGE_DAYS} days, dry run ${DRY_RUN}."

DELETED=0
STATUS=0
while read -r line; do
  [ -n "$line" ] || continue
  if [ "$DELETED" -ge "$MAX_DELETES" ]; then
    echo "MAX_DELETES (${MAX_DELETES}) reached. The next run deletes the rest."
    break
  fi
  id=$(jq -r '.id' <<<"$line")
  echo "delete $(jq -r '"\(.name) [\(.tags | join(","))]"' <<<"$line")"
  if [ "$DRY_RUN" = "false" ] && ! delete_version "$id"; then
    echo "Cannot delete version ${id}." >&2
    STATUS=4
    continue
  fi
  DELETED=$((DELETED + 1))
done < <(printf '%s' "$PLAN" | jq -c '.delete_tagged[]'; printf '%s' "$UNTAGGED" | jq -c '.[]')

echo "${OWNER}/${PACKAGE}: ${DELETED} versions $([ "$DRY_RUN" = "false" ] && echo deleted || echo "would be deleted")."
exit "$STATUS"
