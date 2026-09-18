#!/usr/bin/env bash
# Make room for ES image builds and ES data dirs. Every job calls it; the script decides what to do:
#
#   shared self-hosted box   reclaim dangling layers and build cache only. The daemon and the
#                            system directories belong to every runner on the box.
#   GitHub-hosted VM         delete the preinstalled toolchains and prune the whole daemon, when the
#                            disk is tight. The VM dies with the job.
#   anything else            nothing.
#
# Inside a `container:` job the host toolchains are not on this filesystem, but the bind-mounted
# /var/run/docker.sock controls the host daemon, so a throwaway container with the host root
# mounted deletes them. Nothing here fails the job.
set -euo pipefail
# shellcheck source=ci/log.sh
source "$(dirname "${BASH_SOURCE[0]}")/log.sh"
# shellcheck source=ci/runner-detect.sh
source "$(dirname "${BASH_SOURCE[0]}")/runner-detect.sh"

# Below this many GB free, the levers are worth their wall time. Above it they are not: a reclaim
# costs minutes, so do not run one for space no job is short of.
ROR_DISK_RECLAIM_THRESHOLD_GB="${ROR_DISK_RECLAIM_THRESHOLD_GB:-40}"

if is_shared_docker_host; then
  ci_log "[host] shared self-hosted box: reclaiming only our own docker leftovers"
  df -h / >&2 || true
  docker image prune -f || true
  docker builder prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" || true
  ci_log "[host] after reclaim:"
  df -h / >&2 || true
  exit 0
fi

# Fail closed: everything below deletes system directories or prunes a whole daemon, so "unknown"
# means "skip".
if [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then
  ci_log "not a GitHub-hosted runner (RUNNER_ENVIRONMENT='${RUNNER_ENVIRONMENT:-unset}') - skipping disk reclaim"
  exit 0
fi

avail_gb=$(df --output=avail -BG / 2>/dev/null | tail -1 | tr -dc '0-9' || true)
ci_log "[host] disk before reclaim: ${avail_gb:-<unreadable>}GB free (threshold ${ROR_DISK_RECLAIM_THRESHOLD_GB}GB)"
df -h / >&2 || true

# The measurement fails closed too: without a trustworthy number this script cannot prove the disk
# is tight, and it must not delete anything on a guess.
if ! [[ $avail_gb =~ ^[0-9]+$ ]] || ! [[ $ROR_DISK_RECLAIM_THRESHOLD_GB =~ ^[0-9]+$ ]]; then
  ci_log "[host] cannot trust the free-space check (avail='${avail_gb:-}', threshold='$ROR_DISK_RECLAIM_THRESHOLD_GB') - skipping reclaim"
  exit 0
fi

if [ "$avail_gb" -ge "$ROR_DISK_RECLAIM_THRESHOLD_GB" ]; then
  ci_log "[host] enough free space - skipping reclaim"
  exit 0
fi

# GitHub's ubuntu image ships ~25 GB of toolchains no ROR job uses.
TOOLCHAIN_DIRS='/usr/share/dotnet /usr/local/.ghcup /usr/share/swift /usr/local/share/powershell /usr/local/julia* /opt/microsoft /opt/az /usr/share/chromium /usr/local/lib/android /opt/ghc /usr/local/share/boost /opt/hostedtoolcache'

# --- lever 1: preinstalled toolchains ---------------------------------------------------------
if [ -d /usr/share/dotnet ] || [ -d /usr/local/lib/android ] || [ -d /opt/hostedtoolcache ]; then
  ci_log "[host] freeing preinstalled toolchains"
  # shellcheck disable=SC2086
  sudo rm -rf $TOOLCHAIN_DIRS 2>/dev/null || true
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  # A container: job. The host root is reachable through the daemon the socket controls.
  ci_log "[host] container job: freeing the host's preinstalled toolchains through the docker daemon"
  docker run --rm -v /:/host alpine sh -c "cd /host && rm -rf $(printf '%s ' $TOOLCHAIN_DIRS | sed 's|/|./|; s| /| ./|g')" 2>/dev/null || true
else
  ci_log "[host] no toolchain dirs here and no docker daemon - skipping lever 1"
fi

# --- lever 2: the runner's Docker daemon ------------------------------------------------------
# Volumes are deliberately NOT pruned: testcontainers may already hold one by the time a later
# call runs, and the space is in the images and the build cache anyway.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  ci_log "[docker] pruning unused images, containers, networks and build cache"
  docker system prune -af >/dev/null 2>&1 || true
  docker builder prune -af >/dev/null 2>&1 || true
  docker system df >&2 2>/dev/null || true
else
  ci_log "[docker] no reachable Docker daemon - skipping prune"
fi

ci_log "[host] disk after reclaim:"
df -h / >&2 || true
