#!/usr/bin/env python3
"""Judge a new nightly run record against the rolling history of KPI time series.

Time-based judgement runs ONLY on the pinned nightly agent: a KPI fails when its us_op
exceeds the rolling median of the last 7 comparable records (same env fingerprint, same
benchSuiteSha) by more than max(3 * 1.4826 * MAD, 5% of median). A 30-day linear drift
above 10% raises a warning. Exit codes: 0 = ok, 1 = violation. Plain python3 stdlib only.
"""
import argparse
import glob
import json
import os
import statistics
import sys
from datetime import datetime, timedelta, timezone

MIN_HISTORY = 3
WINDOW = 7
MAD_FACTOR = 3 * 1.4826
MIN_RELATIVE_MARGIN = 0.05
DRIFT_DAYS = 30
DRIFT_LIMIT = 0.10


def parse_kpis(path):
    """Minimal YAML-subset reader for kpis.yml (flat `- key: value` items under `kpis:`)."""
    kpis, current = [], None
    with open(path) as f:
        for raw in f:
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if stripped.startswith("- "):
                current = {}
                kpis.append(current)
                stripped = stripped[2:]
            if current is not None and ":" in stripped:
                key, _, value = stripped.partition(":")
                current[key.strip()] = value.strip()
    return [k for k in kpis if "id" in k and "benchmark" in k]


def load_history(history_glob, fingerprint, suite_sha, exclude_run):
    records = []
    for path in glob.glob(history_glob, recursive=True):
        try:
            with open(path) as f:
                rec = json.load(f)
        except (json.JSONDecodeError, OSError):
            continue
        if rec.get("env", {}).get("fingerprint") != fingerprint:
            continue
        if rec.get("benchSuiteSha") != suite_sha:
            continue
        if (rec.get("timestamp"), rec.get("runId")) == exclude_run:
            continue
        records.append(rec)
    records.sort(key=lambda r: r.get("timestamp", ""))
    return records


def series_of(records, benchmark, metric):
    points = []
    for rec in records:
        value = rec.get("benchmarks", {}).get(benchmark, {}).get(metric)
        if value is not None:
            points.append((rec["timestamp"], float(value), rec.get("srcSha", "?")))
    return points


def parse_ts(value):
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def drift_warning(points, kpi_id):
    cutoff = datetime.now(timezone.utc) - timedelta(days=DRIFT_DAYS)
    recent = [(parse_ts(ts), v) for ts, v, _ in points if parse_ts(ts) >= cutoff]
    if len(recent) < 8:
        return None
    xs = [(t - recent[0][0]).total_seconds() / 86400.0 for t, _ in recent]
    ys = [v for _, v in recent]
    n, mean_x, mean_y = len(xs), statistics.fmean(xs), statistics.fmean(ys)
    denom = sum((x - mean_x) ** 2 for x in xs)
    if denom == 0 or mean_y == 0:
        return None
    slope = sum((xs[i] - mean_x) * (ys[i] - mean_y) for i in range(n)) / denom
    projected = slope * DRIFT_DAYS / mean_y
    if projected > DRIFT_LIMIT:
        return (f"drift alert: {kpi_id} trending {projected * 100:+.1f}% per {DRIFT_DAYS} days "
                f"(slope {slope:+.3f} us/op/day over {n} points)")
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", required=True, help="the new run record (extract.py output)")
    parser.add_argument("--history", required=True,
                        help="glob of history records, e.g. 'history/results/**/*.json'")
    parser.add_argument("--kpis", required=True, help="benchmarks/kpis.yml")
    parser.add_argument("--markdown", default=None, help="write the judgement table here")
    args = parser.parse_args()

    with open(args.record) as f:
        record = json.load(f)
    kpis = [k for k in parse_kpis(args.kpis) if k.get("gate") == "nightly-median"]
    history = load_history(args.history, record["env"]["fingerprint"],
                           record["benchSuiteSha"], (record["timestamp"], record.get("runId")))

    on_azure = bool(os.environ.get("TF_BUILD"))
    violations, warnings, rows = [], [], []
    last_good_sha = history[-1]["srcSha"] if history else None

    for kpi in kpis:
        benchmark, metric, kpi_id = kpi["benchmark"], kpi["metric"], kpi["id"]
        new_value = record.get("benchmarks", {}).get(benchmark, {}).get(metric)
        if new_value is None:
            rows.append((kpi_id, None, None, "missing in record"))
            continue
        points = series_of(history, benchmark, metric)[-WINDOW:]
        if len(points) < MIN_HISTORY:
            rows.append((kpi_id, new_value, None, f"insufficient history ({len(points)})"))
            continue
        values = [v for _, v, _ in points]
        median = statistics.median(values)
        mad = statistics.median([abs(v - median) for v in values])
        threshold = median + max(MAD_FACTOR * mad, MIN_RELATIVE_MARGIN * median)
        if new_value > threshold:
            status = f"VIOLATION (> {threshold:.2f})"
            violations.append((kpi_id, median, new_value))
        else:
            status = "ok"
        rows.append((kpi_id, new_value, median, status))
        drift = drift_warning(series_of(history, benchmark, metric), kpi_id)
        if drift:
            warnings.append(drift)

    lines = [
        "## Nightly KPI judgement (rolling median of last "
        f"{WINDOW}, fingerprint `{record['env']['fingerprint']}`)",
        "",
        "| KPI | new us/op | median us/op | status |",
        "|---|---:|---:|---|",
    ]
    for kpi_id, new_value, median, status in rows:
        new_s = f"{new_value:.2f}" if new_value is not None else "-"
        med_s = f"{median:.2f}" if median is not None else "-"
        lines.append(f"| {kpi_id} | {new_s} | {med_s} | {status} |")
    markdown = "\n".join(lines) + "\n"
    print(markdown)
    if args.markdown:
        os.makedirs(os.path.dirname(os.path.abspath(args.markdown)), exist_ok=True)
        with open(args.markdown, "w") as f:
            f.write(markdown)

    for message in warnings:
        print(f"##vso[task.logissue type=warning]{message}" if on_azure else f"WARNING: {message}")

    if violations:
        for kpi_id, median, new_value in violations:
            delta_pct = ((new_value - median) / median * 100) if median else float('inf')
            message = (f"KPI regression: {kpi_id} {median:.2f} -> {new_value:.2f} us/op "
                       f"({delta_pct:+.1f}% vs rolling median)")
            print(f"##vso[task.logissue type=error]{message}" if on_azure else f"ERROR: {message}",
                  file=sys.stderr if not on_azure else sys.stdout)
        if last_good_sha:
            # Consumed by the nightly pipeline to build the suspect commit range for the GH issue.
            print(f"SUSPECT_RANGE={last_good_sha}..{record['srcSha']}")
        return 1
    print("nightly KPI judgement: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
