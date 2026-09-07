#!/usr/bin/env bash
# Make room for ES image builds and ES data dirs. What there is to reclaim depends on the runner:
#
#   shared self-hosted   the system directories belong to the box, so never touch them. Docker
#                        garbage from earlier runs is ours to reclaim — but only the parts no
#                        other repo's runner can be using: dangling layers and build cache, never
#                        `-a`, never a container we did not start. Selected by ROR_SHARED_DOCKER_HOST=1.
#   ephemeral hosted VM  reclaim both levers below, but only when the disk is actually tight.
#                        The VM dies after the job anyway. Selected by RUNNER_ENVIRONMENT=github-hosted.
#   anything else        reclaim nothing. A developer shell, `act`, or an unlabelled self-hosted
#                        job must never lose its docker images to this script.
#
# On an ephemeral runner two shapes of job call this, and they need different levers:
#
#   * bare runner (e2e_tests): the preinstalled toolchains are visible, so deleting them is what
#     reclaims space. GitHub's ubuntu image ships ~25 GB of dotnet, android, ghc, swift and
#     hostedtoolcache that no ROR job uses.
#   * container: job (it_linux): the steps run inside the toolchains image, where those host
#     directories do not exist. What IS reachable is the runner's Docker daemon, over the
#     bind-mounted /var/run/docker.sock — and that daemon's storage lives on the host's /, the
#     same filesystem the ES images and the ES data dirs fill up. Images in use by the running job
#     container (the toolchains image itself) are never removed by a prune.
#
# Nothing here fails the job. A reclaim is an optimisation; if the disk is genuinely full the build
# says so with a better message than this script could.
set -euo pipefail

# Below this many GB free, the levers are worth their wall time. Above it they are not: a reclaim
# costs minutes, so do not run one for space no job is short of.
ROR_DISK_RECLAIM_THRESHOLD_GB="${ROR_DISK_RECLAIM_THRESHOLD_GB:-40}"

# The shared box comes first: it is self-hosted too, so the guard below would otherwise catch it.
if [ "${ROR_SHARED_DOCKER_HOST:-0}" = "1" ]; then
  echo ">>> [host] shared self-hosted box: reclaiming only our own docker leftovers"
  df -h / || true
  docker image prune -f || true
  docker builder prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" || true
  echo ">>> [host] after reclaim:"
  df -h / || true
  exit 0
fi

# Fail closed: reclaim only when the runner proves it is an ephemeral GitHub-hosted VM.
# RUNNER_ENVIRONMENT is set by the Actions runner itself and is "github-hosted" only on GitHub's
# own VMs. This script deletes system directories and prunes a whole Docker daemon, so "unknown"
# has to mean "skip". Do not set AGENT_ISSELFHOSTED workflow-wide: it turns this script off in
# every job that inherits it.
if [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ] || [ "${AGENT_ISSELFHOSTED:-0}" = "1" ]; then
  echo ">>> not a GitHub-hosted runner (RUNNER_ENVIRONMENT='${RUNNER_ENVIRONMENT:-unset}') - skipping disk reclaim"
  exit 0
fi

avail_gb=$(df --output=avail -BG / 2>/dev/null | tail -1 | tr -dc '0-9' || true)
echo ">>> [host] disk before reclaim: ${avail_gb:-<unreadable>}GB free (threshold ${ROR_DISK_RECLAIM_THRESHOLD_GB}GB)"
df -h / || true

# The measurement fails closed too: without a trustworthy number this script cannot prove the disk
# is tight, and it must not delete anything on a guess.
if ! [[ $avail_gb =~ ^[0-9]+$ ]] || ! [[ $ROR_DISK_RECLAIM_THRESHOLD_GB =~ ^[0-9]+$ ]]; then
  echo ">>> [host] cannot trust the free-space check (avail='${avail_gb:-}', threshold='$ROR_DISK_RECLAIM_THRESHOLD_GB') - skipping reclaim"
  exit 0
fi

if [ "$avail_gb" -ge "$ROR_DISK_RECLAIM_THRESHOLD_GB" ]; then
  echo ">>> [host] enough free space - skipping reclaim"
  exit 0
fi

# --- lever 1: preinstalled toolchains (bare-runner jobs) ---------------------------------------
if [ -d /usr/share/dotnet ] || [ -d /usr/local/lib/android ] || [ -d /opt/hostedtoolcache ]; then
  echo ">>> [host] freeing preinstalled toolchains to fit ES image builds"
  sudo rm -rf \
    /usr/share/dotnet /usr/local/.ghcup /usr/share/swift \
    /usr/local/share/powershell /usr/local/julia* \
    /opt/microsoft /opt/az /usr/share/chromium \
    /usr/local/lib/android /opt/ghc /usr/local/share/boost /opt/hostedtoolcache 2>/dev/null || true
else
  echo ">>> [host] no preinstalled toolchain dirs visible from here (container: job) - skipping"
fi

# --- lever 2: the runner's Docker daemon (reachable from both shapes) ---------------------------
# Volumes are deliberately NOT pruned: testcontainers may already hold one by the time a later
# call runs, and the space is in the images and the build cache anyway.
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo ">>> [docker] pruning unused images, containers, networks and build cache"
  docker system prune -af >/dev/null 2>&1 || true
  docker builder prune -af >/dev/null 2>&1 || true
  docker system df 2>/dev/null || true
else
  echo ">>> [docker] no reachable Docker daemon - skipping prune"
fi

echo ">>> [host] disk after reclaim:"
df -h / || true
