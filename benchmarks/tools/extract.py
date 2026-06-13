#!/usr/bin/env python3
"""Reduce a raw JMH JSON result (-rf json) to a small append-only run record.

The record is what gets committed to the `benchmark-history` orphan branch (one file per
run, ~5-10 KB) and what compare.py / nightly_judge.py / render_dashboard.py consume.
Plain python3 stdlib only.
"""
import argparse
import hashlib
import json
import math
import multiprocessing
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone

SCHEMA_VERSION = 1


def benchmark_key(entry):
    """Canonical series key: fully-qualified method + sorted params, e.g. `Cls.m{a=1,b=2}`."""
    name = entry["benchmark"]
    params = entry.get("params") or {}
    if not params:
        return name
    rendered = ",".join(f"{k}={params[k]}" for k in sorted(params))
    return f"{name}{{{rendered}}}"


def metrics_of(entry):
    primary = entry["primaryMetric"]
    unit = primary.get("scoreUnit", "")
    if unit != "us/op":
        raise SystemExit(f"unexpected primary metric unit '{unit}' for {entry['benchmark']} "
                         "(run JMH in avgt mode with -tu us / OutputTimeUnit MICROSECONDS)")
    error = float(primary.get("scoreError") or 0.0)
    metrics = {
        "us_op": round(float(primary["score"]), 4),
        # JMH reports NaN error for single-iteration runs; keep the record strict-JSON valid.
        "err": 0.0 if math.isnan(error) else round(error, 4),
    }
    alloc = (entry.get("secondaryMetrics") or {}).get("gc.alloc.rate.norm")
    if alloc is not None:
        metrics["b_op"] = round(float(alloc["score"]), 1)
    return metrics


def git(*args, default=None):
    try:
        return subprocess.check_output(["git", *args], text=True,
                                       stderr=subprocess.DEVNULL).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        if default is not None:
            return default
        raise SystemExit(f"git {' '.join(args)} failed and no fallback was provided")


def env_fingerprint():
    jdk = os.environ.get("JAVA_VERSION", "")
    if not jdk:
        try:
            out = subprocess.check_output(["java", "-version"], text=True,
                                          stderr=subprocess.STDOUT)
            jdk = out.splitlines()[0].strip()
        except (subprocess.CalledProcessError, FileNotFoundError):
            jdk = "unknown"
    env = {
        "host": platform.node(),
        "os": f"{platform.system()} {platform.release()}",
        "arch": platform.machine(),
        "cpus": multiprocessing.cpu_count(),
        "jdk": jdk,
    }
    digest_input = "|".join(str(env[k]) for k in ("host", "os", "arch", "cpus", "jdk"))
    env["fingerprint"] = hashlib.sha256(digest_input.encode()).hexdigest()[:12]
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="raw JMH JSON result file")
    parser.add_argument("--output", required=True, help="reduced run-record file to write")
    parser.add_argument("--src-sha", default=None, help="source commit (default: git HEAD)")
    parser.add_argument("--branch", default=None, help="branch name (default: git branch)")
    parser.add_argument("--bench-suite-sha", default=None,
                        help="git tree-hash of benchmarks/src (default: from git)")
    parser.add_argument("--run-id", default=None, help="CI run id (default: $BUILD_BUILDID or '')")
    parser.add_argument("--jmh-args", default="", help="JMH CLI args used, for traceability")
    args = parser.parse_args()

    with open(args.input) as f:
        raw = json.load(f)
    if not raw:
        raise SystemExit("empty JMH result")

    benchmarks = {}
    for entry in raw:
        if entry.get("mode") != "avgt":
            continue
        benchmarks[benchmark_key(entry)] = metrics_of(entry)
    if not benchmarks:
        raise SystemExit("no avgt-mode benchmarks found in JMH result")

    record = {
        "schema": SCHEMA_VERSION,
        "srcSha": args.src_sha or git("rev-parse", "HEAD"),
        "branch": args.branch or git("rev-parse", "--abbrev-ref", "HEAD", default="unknown"),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "runId": args.run_id if args.run_id is not None else os.environ.get("BUILD_BUILDID", ""),
        "benchSuiteSha": args.bench_suite_sha or git("rev-parse", "HEAD:benchmarks/src",
                                                     default="unknown"),
        "env": env_fingerprint(),
        "jmh": {"version": raw[0].get("jmhVersion", "unknown"), "args": args.jmh_args},
        "benchmarks": benchmarks,
    }

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w") as f:
        json.dump(record, f, indent=1, sort_keys=True)
        f.write("\n")
    print(f"wrote {args.output} ({len(benchmarks)} benchmark series)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
