#!/usr/bin/env bash
#
# Reap orphaned testcontainers ES containers on a self-hosted CI agent.
#
# WHY: when Azure hard-cancels / Abandons an IT leg (agent loses contact under load), the Gradle
# worker JVM is SIGKILLed before any in-job cleanup or JVM shutdown hook runs, and Ryuk may die in
# the same teardown. The leg's ES containers then survive forever, pile up across builds, and drag
# the box into a load spiral that starves and Abandons the NEXT heavy leg. The in-pipeline always()
# step (ci/azure-templates/integration-test-steps.yml) handles cancel/fail; it canNOT handle
# Abandoned because no in-job step executes then. This timer-driven reaper is that last safety net.
#
# SAFETY: only removes testcontainers-labelled containers whose age EXCEEDS the leg timeout
# (REAP_MIN_AGE_MIN, default 130 > the 120m job timeout). A legitimately-running leg is always
# younger than that, so a live leg is NEVER touched. Scoped to org.testcontainers=true so it never
# touches the production coolify24 VM or anything non-test on the box.
#
# Install (per agent container, or once on the host if the daemon is shared): see the systemd unit
# at the bottom of this file's header. Idempotent; safe to run by hand.
#
set -euo pipefail

REAP_MIN_AGE_MIN="${REAP_MIN_AGE_MIN:-130}"   # minutes; must be > the 120m IT job timeout
now_epoch=$(date +%s)
reaped=0

# All testcontainers-managed containers (running or exited). Format: <id> <createdAt-RFC3339>.
while read -r id created; do
  [ -z "$id" ] && continue
  # created is e.g. 2026-06-18T00:11:22Z (docker inspect .Created). Compute age in minutes.
  created_epoch=$(date -d "$created" +%s 2>/dev/null || echo 0)
  [ "$created_epoch" -eq 0 ] && continue
  age_min=$(( (now_epoch - created_epoch) / 60 ))
  if [ "$age_min" -ge "$REAP_MIN_AGE_MIN" ]; then
    label=$(docker inspect -f '{{ index .Config.Labels "ror.leg" }}' "$id" 2>/dev/null || echo "?")
    echo ">>> reaping orphan container $id (age ${age_min}m >= ${REAP_MIN_AGE_MIN}m, ror.leg=${label:-none})"
    docker rm -f "$id" >/dev/null 2>&1 && reaped=$((reaped+1)) || true
  fi
done < <(docker ps -aq --filter "label=org.testcontainers=true" \
           | xargs -r docker inspect -f '{{.Id}} {{.Created}}' 2>/dev/null)

# Dangling images/build cache left by removed ES image builds (safe: never removes in-use layers).
docker image prune -f   >/dev/null 2>&1 || true
docker builder prune -f >/dev/null 2>&1 || true

echo ">>> reap complete: ${reaped} orphan container(s) removed (threshold ${REAP_MIN_AGE_MIN}m)"

# ---------------------------------------------------------------------------------------------------
# systemd install (run inside each az-ror-es-* agent container, or once on the host if daemon shared):
#
#   sudo install -m0755 ci/reap-orphan-es-containers.sh /usr/local/sbin/reap-orphan-es-containers.sh
#   sudo tee /etc/systemd/system/reap-orphan-es.service >/dev/null <<'EOF'
#   [Unit]
#   Description=Reap orphaned testcontainers ES containers (CI leak guard)
#   [Service]
#   Type=oneshot
#   ExecStart=/usr/local/sbin/reap-orphan-es-containers.sh
#   EOF
#   sudo tee /etc/systemd/system/reap-orphan-es.timer >/dev/null <<'EOF'
#   [Unit]
#   Description=Run orphan-ES reaper every 15 min
#   [Timer]
#   OnBootSec=5min
#   OnUnitActiveSec=15min
#   Persistent=true
#   [Install]
#   WantedBy=timers.target
#   EOF
#   sudo systemctl daemon-reload && sudo systemctl enable --now reap-orphan-es.timer
# ---------------------------------------------------------------------------------------------------
