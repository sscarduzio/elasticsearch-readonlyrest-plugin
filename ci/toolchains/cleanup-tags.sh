#!/usr/bin/env bash
# Deletes the toolchains image tags (<prefix>-deps-<hash>, ci/toolchains/deps-tag.sh) last pushed more than
# MAX_AGE_DAYS (30) ago. It never deletes <prefix>-develop or the newest -deps- tag.
# Env: DOCKER_HUB_USER, DOCKER_HUB_DELETE_TOKEN (a token with the Delete scope). Without the token it
#      only lists the tags. NOW_EPOCH overrides the clock (tests).
set -euo pipefail
cd "$(dirname "$0")/../.."
. ci/toolchains/image.env
# shellcheck source=ci/log.sh
source ci/log.sh

repo=${TOOLCHAINS_TAG_PREFIX%:*} prefix="${TOOLCHAINS_TAG_PREFIX##*:}-deps-"
api="https://hub.docker.com/v2/namespaces/${repo%%/*}/repositories/${repo#*/}/tags"
cutoff=$(( ${NOW_EPOCH:-$(date -u +%s)} - ${MAX_AGE_DAYS:-30} * 86400 ))

tags=$(curl -fsS "${api}?page_size=100" | jq -c '.results') # one page: the cleanup keeps the count low
old=$(jq -r --arg p "$prefix" --argjson cutoff "$cutoff" '
  [.[] | select(.name | startswith($p)) | {name, t: (.tag_last_pushed | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)}]
  | sort_by(.t) | .[:-1] | .[] | select(.t < $cutoff) | .name' <<<"$tags")

[ -n "$old" ] || { ci_log "No toolchains image tag to delete."; exit 0; }
if [ -z "${DOCKER_HUB_DELETE_TOKEN:-}" ]; then
  ci_log "No DOCKER_HUB_DELETE_TOKEN. These tags would go:"; echo "$old"; exit 0
fi
jwt=$(jq -n --arg u "${DOCKER_HUB_USER:?}" --arg p "$DOCKER_HUB_DELETE_TOKEN" '{username: $u, password: $p}' |
      curl -fsS -H 'Content-Type: application/json' -d @- https://hub.docker.com/v2/users/login | jq -r '.token')
echo "::add-mask::$jwt"
status=0
while read -r t; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $jwt" "$api/$t")
  if [ "$code" = 204 ] || [ "$code" = 200 ]; then ci_log "Deleted $t."; else ci_log "Cannot delete $t: HTTP $code."; status=1; fi
done <<<"$old"
exit "$status"
