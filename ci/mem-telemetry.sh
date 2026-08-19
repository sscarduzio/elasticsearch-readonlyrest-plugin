#!/bin/bash
# Memory telemetry for the integration CI legs.
#
# A leg runs ~9 JVMs plus up to 8 ES containers on a 16GB runner. When the tail of that sum
# crosses physical RAM, the kernel OOM killer SIGKILLs the fattest process and the job dies
# with a bare "exit code 137" and no evidence. This sampler records, every 10 seconds:
#   * host MemAvailable/SwapFree (/proc/meminfo is not namespaced, so the values are host-wide
#     even though the job runs inside the toolchains container)
#   * memory PSI (pressure-stall, /proc/pressure/memory) where the kernel exposes it
#   * the top processes by RSS in the job container's PID namespace (the gradle/JVM stack)
#   * docker stats for all containers (the ES/deps containers are siblings on the host daemon)
#
# Usage:
#   ci/mem-telemetry.sh start <logfile>    prints the sampler PID; runs until stopped or 3h
#   ci/mem-telemetry.sh stop <pid>
#   ci/mem-telemetry.sh report <logfile>   worst-pressure sample + end-of-run OOM forensics
set -u

INTERVAL_S=10
MAX_LIFETIME_S=10800   # self-terminate after 3h: never outlive a hung/cancelled job

sample_once() {
  local ts avail swapfree psi
  ts=$(date -u +%FT%TZ)
  # MemAvailable, not MemFree: it includes reclaimable page cache, which is what the OOM killer
  # effectively has to work with.
  read -r avail swapfree < <(awk '/^MemAvailable:/{a=int($2/1024)} /^SwapFree:/{s=int($2/1024)} END{print a, s}' /proc/meminfo)
  psi=$(awk '/^some/{for(i=1;i<=NF;i++) if($i ~ /^avg10=/){sub("avg10=","",$i); print $i}}' /proc/pressure/memory 2>/dev/null || echo "n/a")
  echo "=== $ts avail_mb=$avail swapfree_mb=$swapfree psi_some_avg10=$psi"
  # Top of THIS PID namespace = the gradle stack (orchestrator, shard JVMs, test workers).
  ps -eo rss=,pid=,args= --sort=-rss | head -12 | awk '{rss=int($1/1024); pid=$2; $1=""; $2=""; cmd=substr($0,3,90); printf "P rss_mb=%s pid=%s %s\n", rss, pid, cmd}'
  # Sibling containers (ES nodes, LDAP, wiremock, ...). --no-stream: one shot, ~1s.
  docker stats --no-stream --format 'D {{.Name}} {{.MemUsage}}' 2>/dev/null || echo "D docker-stats-unavailable"
}

case "${1:-}" in
  start)
    LOG=${2:?usage: mem-telemetry.sh start <logfile>}
    mkdir -p "$(dirname "$LOG")"
    # setsid: the collector below is a second process, so `stop` signals the whole group.
    setsid "$0" _collect "$LOG" >>"$LOG" 2>&1 &
    disown
    echo $!
    ;;

  _collect)
    LOG=${2:?internal}
    # A container's death has to be recorded WHILE it is visible: by the time the report step runs,
    # Ryuk has reaped everything, so `docker ps -a` finds nothing and an OOM kill leaves no trace.
    # `docker events` streams die/oom as they happen, and costs nothing between events.
    docker events --filter event=die --filter event=oom \
      --format 'X {{.Action}} name={{index .Actor.Attributes "name"}} exit={{index .Actor.Attributes "exitCode"}}' \
      >>"$LOG" 2>/dev/null &
    # The collector must not outlive this loop: `stop` kills the process group, but the lifetime cap
    # below returns normally, and a background child survives its parent's exit. The PID is captured
    # in a variable, not read as $! from inside the trap, where it would expand at trap time.
    events_pid=$!
    trap 'kill "$events_pid" 2>/dev/null' EXIT
    end=$((SECONDS + MAX_LIFETIME_S))
    while [ "$SECONDS" -lt "$end" ]; do
      sample_once
      sleep "$INTERVAL_S"
    done
    ;;

  stop)
    PID=${2:?usage: mem-telemetry.sh stop <pid>}
    # Negative PID = the whole group, so the docker-events collector dies with the sampler.
    kill -TERM -- "-$PID" 2>/dev/null || kill "$PID" 2>/dev/null || true
    ;;

  report)
    LOG=${2:?usage: mem-telemetry.sh report <logfile>}
    if [ ! -s "$LOG" ]; then echo "no telemetry recorded at $LOG"; exit 0; fi
    echo "##### Worst memory-pressure sample (lowest host MemAvailable) #####"
    # Block = one sample (=== header + P/D lines). Print the block with the lowest avail_mb.
    # BEGIN{n=0} is load-bearing: an uninitialized n is "" as an array SUBSCRIPT (not 0), so the
    # first saved block would land under key "" and index 0 would read back empty.
    awk '
      BEGIN { n=0 }
      /^=== / { if (block != "" ) { blocks[n]=block; avails[n]=avail; n++ }
                block=$0 ORS; avail=$0; sub(/.*avail_mb=/,"",avail); sub(/ .*/,"",avail); next }
      { block=block $0 ORS }
      END { if (block != "") { blocks[n]=block; avails[n]=avail; n++ }
            min=-1
            for (i=0;i<n;i++) if (min<0 || avails[i]+0 < avails[min]+0) min=i
            if (min>=0) print blocks[min]
            print "samples=" n
            low=0; for (i=0;i<n;i++) if (avails[i]+0 < 1024) low++
            print "samples_below_1gb_available=" low }
    ' "$LOG"
    echo "##### Last sample before shutdown #####"
    awk '/^=== /{block=$0 ORS; next} {block=block $0 ORS} END{printf "%s", block}' "$LOG"
    echo "##### Kernel OOM-killer evidence (host ring buffer; may be restricted in-container) #####"
    # Capture first: `| tail` exits 0 even on empty input, so `|| echo` alone can never fire.
    OOM_LINES=$(dmesg 2>/dev/null | grep -iE "out of memory|oom[-_]kill|killed process" | tail -20)
    if [ -n "$OOM_LINES" ]; then echo "$OOM_LINES"; else echo "no OOM events visible (or dmesg restricted)"; fi
    echo "##### Container deaths recorded during the run (docker events) #####"
    DEATHS=$(grep '^X ' "$LOG" | sort | uniq -c | sort -rn)
    if [ -n "$DEATHS" ]; then echo "$DEATHS"; else echo "no container died during the run"; fi
    echo "##### Containers still present at report time: OOMKilled / exit codes #####"
    if [ -n "${ROR_CI_JOB_ID:-}" ]; then
      docker ps -a --filter "label=ror.ci-job=$ROR_CI_JOB_ID" --format '{{.ID}} {{.Names}}' 2>/dev/null \
        | while read -r id name; do
            if out=$(docker inspect -f "{{.Name}} oomkilled={{.State.OOMKilled}} exit={{.State.ExitCode}} status={{.State.Status}}" "$id" 2>&1); then
              echo "$out"
            elif printf '%s' "$out" | grep -qiE "no such (object|container)"; then
              echo "$name ($id) is gone - reaped before it could be inspected"
            else
              echo "$name ($id) could not be inspected: $out"
            fi
          done
    else
      echo "ROR_CI_JOB_ID not set - skipping container inspection"
    fi
    # This report is diagnostics. The workflow step runs it under `bash -e`, so any non-zero exit
    # here fails a leg whose tests all passed.
    exit 0
    ;;

  *)
    echo "usage: $0 start <logfile> | stop <pid> | report <logfile>"; exit 2
    ;;
esac
