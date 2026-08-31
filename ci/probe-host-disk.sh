#!/usr/bin/env bash
# Reports Docker storage placement and large preinstalled toolchain directories on the runner.
set -uo pipefail

echo "================ HOST DISK PROBE ================"
echo "## mounts (which filesystem holds /var/lib/docker?)"
DOCKER_ROOT=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)
DOCKER_DEV=$(df -P "$DOCKER_ROOT" 2>/dev/null | awk 'NR==2{print $1}')
echo "docker root: $DOCKER_ROOT on device: $DOCKER_DEV"
df -hP / /mnt "$DOCKER_ROOT" 2>/dev/null | sort -u

echo
echo "## candidate toolchain directories by size and device"
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
trap 'rm -f "$TMP"' EXIT
for candidate in "${CANDIDATES[@]}"; do
  for path in $candidate; do
    [ -e "$path" ] || continue
    size=$(timeout 20 du -skx "$path" 2>/dev/null | awk '{print $1}')
    [ -n "$size" ] || size=0
    device=$(df -P "$path" 2>/dev/null | awk 'NR==2{print $1}')
    same=$([ "$device" = "$DOCKER_DEV" ] && echo "yes" || echo "no")
    echo "$size|$path|$device|$same" >> "$TMP"
  done
done
sort -t'|' -k1 -rn "$TMP" | awk -F'|' '$1>=524288{printf "%-32s %10d %12s %s\n",$2,$1,$3,$4}'

echo
RECLAIM=$(awk -F'|' '$4=="yes"{sum+=$1} END{printf "%.1f", sum/1048576}' "$TMP")
echo "## Total reclaimable space on Docker's device: ${RECLAIM} GB"
echo "================ END HOST DISK PROBE ================"
