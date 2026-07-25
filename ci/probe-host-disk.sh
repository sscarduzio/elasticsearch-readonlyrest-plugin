#!/usr/bin/env bash
# Azure-hosted-runner disk probe. Runs as a BARE step (host fs, not in container).
# Measures: (a) where the Docker overlay lives, (b) sizes of the known-fat dirs ON THAT mount,
# ranked, with the free-space they'd reclaim. Self-terminates: this is a one-shot recon, exit 1.
set -uo pipefail

echo "================ HOST DISK PROBE ================"
echo "## mounts (which fs holds /var/lib/docker?)"
DOCKER_ROOT=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)
DOCKER_DEV=$(df -P "$DOCKER_ROOT" 2>/dev/null | awk 'NR==2{print $1}')
echo "docker root: $DOCKER_ROOT  on device: $DOCKER_DEV"
df -hP / /mnt "$DOCKER_ROOT" 2>/dev/null | sort -u

echo
echo "## candidate fat dirs (allowlist), size + which device they sit on"
# Known-fat on MS-hosted ubuntu images. max-depth via du -s (one stat per dir, no deep walk).
CANDIDATES=(
  /usr/share/dotnet
  /opt/hostedtoolcache
  /usr/local/lib/android
  /usr/share/swift
  /opt/ghc
  /usr/local/.ghcup
  /usr/local/share/powershell
  /usr/local/share/chromium
  /usr/local/share/boost
  /usr/lib/jvm
  /usr/local/julia*
  /usr/local/graalvm
  /opt/az
  /opt/microsoft
)
printf '%-32s %10s %12s %s\n' DIR SIZE_KB DEVICE SAME_AS_DOCKER
TMP=$(mktemp)
for d in "${CANDIDATES[@]}"; do
  for path in $d; do
    [ -e "$path" ] || continue
    # du -sk is one pass; cap with timeout so a pathological dir can't hang the probe
    sz=$(timeout 20 du -skx "$path" 2>/dev/null | awk '{print $1}')
    [ -z "$sz" ] && sz=0
    dev=$(df -P "$path" 2>/dev/null | awk 'NR==2{print $1}')
    same=$([ "$dev" = "$DOCKER_DEV" ] && echo "YES" || echo "no")
    echo "$sz|$path|$dev|$same" >> "$TMP"
  done
done
# rank desc by size, skip <512MB (slim dirs not worth scripting an rm for)
sort -t'|' -k1 -rn "$TMP" | awk -F'|' '$1>=524288{printf "%-32s %10d %12s %s\n",$2,$1,$3,$4}'

echo
RECLAIM=$(sort -t'|' -k1 -rn "$TMP" | awk -F'|' '$4=="YES"{s+=$1} END{printf "%.1f", s/1048576}')
echo "## TOTAL reclaimable ON DOCKER'S DEVICE: ${RECLAIM} GB"
echo "## (dirs on other devices do NOT help the overlay fs)"
rm -f "$TMP"
echo "================ END PROBE (killing build) ================"
exit 1   # one-shot recon: we only wanted the chart
