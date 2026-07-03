#!/usr/bin/env bash
# Free preinstalled toolchains on Azure hosted VMs to make room for ES image builds.
# Runs on the HOST (not in the container) — caller must set target: host in the Azure step.
# Skipped on self-hosted agents (shared system dirs).
set -euo pipefail

if [ "${AGENT_ISSELFHOSTED:-0}" != "1" ]; then
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
  echo ">>> self-hosted agent — skipping host toolchain reclaim (shared system dirs)"
fi