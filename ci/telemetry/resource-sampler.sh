#!/usr/bin/env bash
# Records the machine's CPU time and IO wait every 5 s for the length of a
# job and prints the series at the end. `start` is the step after checkout,
# `report` the last step of the job (with `if: always()`).
#
# One sample per line: epoch seconds, CPU busy microseconds, IO stall
# microseconds (PSI "some": time any task waited on IO), iowait microseconds.
# All four are cumulative counters. A reader takes the deltas between the
# samples that bracket a step, with the step times from the jobs API, and
# gets CPU, IO wait and wall time for every step without a step of its own.
#
# The counters cover what the job pays for. On a hosted VM that is the VM:
# /proc/stat and /proc/pressure/io are machine-wide, also from inside a job
# container. On a self-hosted Incus container /proc/stat and /proc/pressure
# show the host, shared by every runner on it, so there the container's own
# cgroup supplies both counters and iowait is not available.
set -u
dir="${RUNNER_TEMP:-/tmp}/host-telemetry/resource-sampler"

sample() {
  local cpu iowait io
  if [ -e /dev/incus ] || [ -e /dev/lxd ]; then
    cpu=$(awk '/^usage_usec/ {print $2}' /sys/fs/cgroup/cpu.stat 2>/dev/null)
    io=$(awk '/^some/ {sub("total=", "", $5); print $5}' /sys/fs/cgroup/io.pressure 2>/dev/null)
    iowait=0
  else
    read -r cpu iowait < <(awk '/^cpu / {print ($2+$3+$4+$7+$8+$9) * 10000, $6 * 10000}' /proc/stat)
    io=$(awk '/^some/ {sub("total=", "", $5); print $5}' /proc/pressure/io 2>/dev/null)
  fi
  printf '%s %s %s %s\n' "$(date +%s)" "${cpu:-0}" "${io:-0}" "${iowait:-0}"
}

case "${1:-}" in
  start)
    mkdir -p "$dir"
    (
      trap 'exit 0' TERM
      end=$((SECONDS + 4 * 3600))
      while [ "$SECONDS" -lt "$end" ]; do sample; sleep 5; done
    ) >"$dir/samples" 2>/dev/null &
    echo $! >"$dir/pid"
    disown
    echo "resource sampler started: pid $(cat "$dir/pid"), $(nproc) cpus"
    ;;
  report)
    if [ ! -s "$dir/samples" ]; then echo "no resource samples: the sampler did not start"; exit 0; fi
    kill "$(cat "$dir/pid" 2>/dev/null)" 2>/dev/null
    sample >>"$dir/samples"
    read -r t0 c0 i0 w0 < <(head -1 "$dir/samples")
    read -r t1 c1 i1 w1 < <(tail -1 "$dir/samples")
    line="resource totals: wall=$((t1 - t0))s cpu=$(( (c1 - c0) / 1000000 ))s io_stall=$(( (i1 - i0) / 1000000 ))s iowait=$(( (w1 - w0) / 1000000 ))s cpus=$(nproc)"
    echo "$line"
    echo "::group::resource samples (epoch cpu_usec io_stall_usec iowait_usec)"
    cat "$dir/samples"
    echo "::endgroup::"
    [ -n "${GITHUB_STEP_SUMMARY:-}" ] && printf '%s\n' "$line" >>"$GITHUB_STEP_SUMMARY"
    exit 0
    ;;
  *)
    echo "usage: $0 start|report" >&2; exit 2 ;;
esac
