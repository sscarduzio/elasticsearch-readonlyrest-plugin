#!/usr/bin/env python3
"""Rank CI steps by IO wait / CPU time and by idle CPU.

Reads the last N successful runs of each workflow, the step timings from the
jobs API, and the `resource samples` group that resource-sampler.sh prints
at the end of each job log. For every step it takes the sample deltas
between the step's start and end.

usage: step-cost.py [-n RUNS] [--top K] owner/repo:workflow.yml ...
"""
import argparse, io, json, re, subprocess, sys, zipfile
from datetime import datetime, timezone

def gh(path, raw=False):
    out = subprocess.run(["gh", "api", path], capture_output=True, check=True).stdout
    return out if raw else json.loads(out)

def ts(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()

SAMPLE = re.compile(r"^(?:\S+\s)?(\d{10}) (\d+) (\d+) (\d+)$", re.M)
CPUS = re.compile(r"resource totals: .*cpus=(\d+)")

def job_samples(repo, job_id):
    """(samples, cpus) from the job log; samples are (epoch, cpu_usec, io_wait_usec)."""
    try:
        log = gh(f"repos/{repo}/actions/jobs/{job_id}/logs", raw=True).decode(errors="replace")
    except subprocess.CalledProcessError:
        return [], 0
    if "resource samples" not in log:
        return [], 0
    tail = log[log.index("resource samples"):]
    tail = tail[: tail.find("endgroup") if "endgroup" in tail else None]
    # PSI is the IO wait; where the kernel has none (all zero) iowait stands in.
    raw = [(int(a), int(b), int(c), int(d)) for a, b, c, d in SAMPLE.findall(tail)]
    psi = any(r[2] for r in raw)
    samples = [(t, c, io if psi else w) for t, c, io, w in raw]
    m = CPUS.search(log)
    return samples, int(m.group(1)) if m else 0

def at(samples, t):
    """Counter values interpolated at epoch t; clamps to the series ends."""
    if t <= samples[0][0]: return samples[0][1], samples[0][2]
    if t >= samples[-1][0]: return samples[-1][1], samples[-1][2]
    for (t0, c0, i0), (t1, c1, i1) in zip(samples, samples[1:]):
        if t0 <= t <= t1:
            f = (t - t0) / (t1 - t0) if t1 > t0 else 0
            return c0 + f * (c1 - c0), i0 + f * (i1 - i0)
    return samples[-1][1], samples[-1][2]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("targets", nargs="+", help="owner/repo:workflow.yml")
    ap.add_argument("-n", type=int, default=3, help="successful runs per workflow")
    ap.add_argument("--top", type=int, default=10)
    ap.add_argument("--min-wall", type=int, default=30, help="ignore shorter steps (s)")
    a = ap.parse_args()
    rows = {}
    for target in a.targets:
        repo, wf = target.split(":")
        runs = gh(f"repos/{repo}/actions/workflows/{wf}/runs?status=success&per_page={a.n}")["workflow_runs"]
        for run in runs:
            for job in gh(f"repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100")["jobs"]:
                samples, cpus = job_samples(repo, job["id"])
                if len(samples) < 2:
                    continue
                for st in job["steps"]:
                    if not (st.get("started_at") and st.get("completed_at")): continue
                    t0, t1 = ts(st["started_at"]), ts(st["completed_at"])
                    wall = t1 - t0
                    if wall < a.min_wall or t1 < samples[0][0] or t0 > samples[-1][0]:
                        continue
                    (c0, i0), (c1, i1) = at(samples, t0), at(samples, t1)
                    key = (repo.split("/")[1], job["name"], st["name"], ",".join(job["labels"]))
                    r = rows.setdefault(key, {"n": 0, "wall": 0, "cpu": 0, "io": 0, "cpus": cpus})
                    r["n"] += 1; r["wall"] += wall; r["cpu"] += (c1 - c0) / 1e6; r["io"] += (i1 - i0) / 1e6
    if not rows:
        sys.exit("no instrumented jobs found (no 'resource samples' group in any job log)")
    out = []
    for (repo, job, step, labels), r in rows.items():
        n = r["n"]; wall, cpu, iow = r["wall"]/n, r["cpu"]/n, r["io"]/n
        cap = wall * r["cpus"] if r["cpus"] else 0
        out.append(dict(repo=repo, job=job, step=step, labels=labels, n=n, wall=wall, cpu=cpu, io=iow,
                        io_per_cpu=iow / cpu if cpu > 0 else float("inf"),
                        cpu_util=cpu / cap if cap else 0))
    def table(title, key, fmt):
        print(f"\n== {title} (top {a.top}, steps ≥ {a.min_wall} s, avg of n runs) ==")
        print(f"{'ratio':>7} {'wall':>6} {'cpu s':>7} {'io s':>6} {'util':>5}  repo/job/step [labels]")
        for r in sorted(out, key=key, reverse=True)[: a.top]:
            print(f"{fmt(r):>7} {r['wall']:>6.0f} {r['cpu']:>7.0f} {r['io']:>6.0f} {r['cpu_util']*100:>4.0f}%  "
                  f"{r['repo']}/{r['job']}/{r['step']} [{r['labels']}] n={r['n']}")
    table("IO wait / CPU time", lambda r: (r["io_per_cpu"], r["wall"]), lambda r: f"{r['io_per_cpu']:.2f}")
    table("idle CPU: wall×cpus − cpu, seconds (what you pay for and do not use)",
          lambda r: r["wall"] * (1 - r["cpu_util"]), lambda r: f"{r['wall']*(1-r['cpu_util']):.0f}")

if __name__ == "__main__":
    main()
