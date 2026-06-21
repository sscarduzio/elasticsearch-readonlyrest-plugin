#!/usr/bin/env bash
#
# Reap orphaned testcontainers ES containers AND orphaned Gradle test JVMs on a self-hosted CI agent.
# The host MUST be able to self-heal: Azure can zombify legs in ways no in-job hook catches —
# Abandoned legs, supersede churn, manual UI cancels, and cancel-livelocks where the server marks a
# build cancelling but the signal never reaches the agent (its JVMs keep running). This timer-driven
# reaper is the host-side last line of defense so clicking around in Azure can't drift the box to 100%
# disk / load spiral.
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

REAP_MIN_AGE_MIN="${REAP_MIN_AGE_MIN:-130}"        # minutes; must be > the 120m IT job timeout
REAP_IMAGE_MAX_AGE="${REAP_IMAGE_MAX_AGE:-180m}"   # prune unused ES images older than this (> longest leg)
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

# Reap orphaned Gradle test JVMs (worker + wrapper) whose leg is surely over. A leg JVM older than
# the max leg time can only be a leftover from a killed/cancelled/abandoned leg whose process tree
# outlived the Azure job — these eat CPU/RAM/disk and (the 2026-06-19 incident) survive even an
# agent-service stop or a manual Azure cancel that never reached the agent. Containers-only reaping
# missed them. Age-gated by process elapsed seconds so a live leg (always younger) is never killed.
reap_age_sec=$(( REAP_MIN_AGE_MIN * 60 ))
jvm_reaped=0
for pid in $(pgrep -f 'GradleWorkerMain|GradleWrapperMain|GradleDaemon' 2>/dev/null); do
  ets=$(ps -o etimes= -p "$pid" 2>/dev/null | tr -d ' '); [ -z "$ets" ] && continue
  if [ "$ets" -ge "$reap_age_sec" ]; then
    echo ">>> reaping orphan gradle JVM pid $pid (age $((ets/60))m >= ${REAP_MIN_AGE_MIN}m)"
    # Kill the whole process group (-pid) so worker children die too; SIGKILL — it's already orphaned.
    kill -KILL -- "-$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ')" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    jvm_reaped=$((jvm_reaped+1))
  fi
done

# Reclaim disk from leaked ES images. Each build bakes a content-hashed `ror-it-es:<hash>` image;
# they're TAGGED (not dangling) so plain `image prune -f` misses them and they pile up until the disk
# fills (observed: 180-200 images, 766G, agents at 100% -> every leg fails "No space left on
# device"). `image prune -af --filter until=` removes any image UNUSED by a running container and
# older than the window, so a live build's own freshly-built image (younger than the window, or
# in-use) is never touched. Window > the longest leg so a slow leg can't have its image yanked.
docker image prune -af --filter "until=${REAP_IMAGE_MAX_AGE:-180m}" >/dev/null 2>&1 || true
docker image prune -f   >/dev/null 2>&1 || true   # leftover dangling layers
docker builder prune -f >/dev/null 2>&1 || true

# Reclaim leaked per-JVM test networks. Each IT worker JVM creates one UUID-named bridge labeled
# `ror-test-jvm` (TestNetwork.perJvm); if Ryuk dies / a leg is Abandoned, these survive forever and
# eventually exhaust the bridge address pool (~31 nets) -> new network creation fails -> every leg
# breaks. `network prune` only removes networks with NO connected containers, so a live leg's network
# (its ES containers are attached) is never touched; the label filter additionally guarantees we only
# ever consider ROR test networks, never the production coolify bridge.
docker network prune -f --filter "label=ror-test-jvm" >/dev/null 2>&1 || true

echo ">>> reap complete: ${reaped} orphan container(s) + ${jvm_reaped} orphan gradle JVM(s) removed (threshold ${REAP_MIN_AGE_MIN}m); images + networks pruned"

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
