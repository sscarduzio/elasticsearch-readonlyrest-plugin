#!/usr/bin/env bash
# Make room for ES image builds. What there is to reclaim depends on the runner:
#
#   ephemeral hosted VM  the preinstalled toolchains nobody here uses. Delete them; the VM dies
#                        after the job anyway.
#   shared self-hosted   the system directories belong to the box, so never touch them. Docker
#                        garbage from earlier runs is ours to reclaim — but only the parts no
#                        other repo's runner can be using: dangling layers and build cache, never
#                        `-a`, never a container we did not start.
#
# Nothing here fails the job. A reclaim is an optimisation; if the disk is genuinely full the build
# says so with a better message than this script could.
set -euo pipefail

if [ "${ROR_SHARED_DOCKER_HOST:-0}" = "1" ]; then
  echo ">>> [host] shared self-hosted box: reclaiming only our own docker leftovers"
  df -h /
  docker image prune -f || true
  docker builder prune -f --keep-storage "${BUILDX_KEEP_STORAGE:-5GB}" || true
  echo ">>> [host] after reclaim:"
  df -h /
elif [ "${AGENT_ISSELFHOSTED:-0}" != "1" ]; then
  echo ">>> [host] freeing preinstalled toolchains to fit ES image builds"
  df -h /
  sudo rm -rf \
    /usr/share/dotnet /usr/local/.ghcup /usr/share/swift \
    /usr/local/share/powershell /usr/local/julia* \
    /opt/microsoft /opt/az /usr/share/chromium \
    /usr/local/lib/android /opt/ghc /usr/local/share/boost /opt/hostedtoolcache 2>/dev/null || true
  echo ">>> [host] after reclaim:"
  df -h /
else
  echo ">>> self-hosted runner - skipping host toolchain reclaim"
fi
