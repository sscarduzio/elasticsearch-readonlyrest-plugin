#!/usr/bin/env bash
# Prints the Docker Hub digest of <repository>:<tag>, or nothing when the tag does not exist.
# A HEAD request does not count against the Docker Hub pull limit. Exit 1 when Docker Hub cannot answer.
set -uo pipefail
ref=${1:?Usage: $0 <repository>:<tag>}
repo=${ref%:*} tag=${ref##*:}

token=$(curl -fsS --max-time 20 \
  "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull" | jq -r '.token') || exit 1
headers=$(curl -sS --max-time 20 -I -o /dev/stdout -w '%{http_code}\n' \
  -H "Authorization: Bearer $token" \
  -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
  "https://registry-1.docker.io/v2/${repo}/manifests/${tag}") || exit 1
case "$(tail -1 <<<"$headers")" in
  200) tr -d '\r' <<<"$headers" | sed -n 's/^docker-content-digest: *//Ip' | head -1 ;;
  404) ;;
  *) exit 1 ;;
esac
