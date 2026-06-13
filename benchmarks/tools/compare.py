#!/usr/bin/env python3
"""Compare a run record's allocations (B/op) against baselines/alloc-baseline.json.

The PR-level gate: allocations are deterministic enough to judge on shared agents.
Exit codes: 0 = pass, 1 = regression found. In advisory mode the pipeline swallows the
exit code (`continueOnError: true`) but the ##vso warnings still surface on the build.
Plain python3 stdlib only.
"""
import argparse
import json
import os
import sys


def load(path):
    with open(path) as f:
        return json.load(f)


def fmt_bytes(value):
    return f"{value:,.1f}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", required=True, help="reduced run record (extract.py output)")
    parser.add_argument("--baseline", required=True, help="baselines/alloc-baseline.json")
    parser.add_argument("--markdown", default=None, help="write the comparison table here")
    parser.add_argument("--write-baseline", action="store_true",
                        help="regenerate the baseline from this record (review like code)")
    args = parser.parse_args()

    record = load(args.record)
    measured = {k: v for k, v in record["benchmarks"].items() if "b_op" in v}

    if args.write_baseline:
        baseline = load(args.baseline) if os.path.exists(args.baseline) else {}
        new_baseline = {
            "schema": 1,
            # An explicit --write-baseline is the authoritative re-seed (reviewed like code),
            # so it clears the provisional flag rather than inheriting the old value forever.
            "provisional": False,
            "generatedAt": record["timestamp"],
            "source": {
                "srcSha": record["srcSha"],
                "env": record["env"],
                "jmhArgs": record["jmh"]["args"],
            },
            "tolerancePct": baseline.get("tolerancePct", 5.0),
            "toleranceAbsBytes": baseline.get("toleranceAbsBytes", 64),
            "benchmarks": {k: {"b_op": v["b_op"]} for k, v in sorted(measured.items())},
        }
        with open(args.baseline, "w") as f:
            json.dump(new_baseline, f, indent=1, sort_keys=True)
            f.write("\n")
        print(f"baseline rewritten with {len(measured)} entries: {args.baseline}")
        return 0

    baseline = load(args.baseline)
    tol_pct = float(baseline.get("tolerancePct", 5.0))
    tol_abs = float(baseline.get("toleranceAbsBytes", 64))
    on_azure = bool(os.environ.get("TF_BUILD"))

    # B/op can shift across arch/OS, so warn (never fail) when this run's env differs from the baseline's.
    base_env, rec_env = baseline.get("source", {}).get("env", {}), record.get("env", {})
    mismatched = [k for k in ("arch", "os") if base_env.get(k) and rec_env.get(k) and base_env[k] != rec_env[k]]
    if mismatched:
        diffs = ", ".join(f"{k}: baseline={base_env[k]!r} vs run={rec_env[k]!r}" for k in mismatched)
        message = f"alloc baseline env mismatch ({diffs}); regenerate with compare.py --write-baseline on the CI agent"
        print(f"##vso[task.logissue type=warning]{message}" if on_azure else f"WARNING: {message}",
              file=sys.stdout if on_azure else sys.stderr)

    rows, regressions, improvements, missing = [], [], [], []
    for key, ref in sorted(baseline.get("benchmarks", {}).items()):
        ref_bop = float(ref["b_op"])
        got = measured.get(key)
        if got is None:
            rows.append((key, ref_bop, None, None, "MISSING"))
            missing.append(key)
            continue
        got_bop = float(got["b_op"])
        delta = got_bop - ref_bop
        delta_pct = (delta / ref_bop * 100.0) if ref_bop else 0.0
        allowed = max(ref_bop * tol_pct / 100.0, tol_abs)
        if delta > allowed:
            status = "REGRESSION"
            regressions.append((key, ref_bop, got_bop, delta_pct))
        elif delta < -allowed:
            status = "improved"
            improvements.append((key, ref_bop, got_bop, delta_pct))
        else:
            status = "ok"
        rows.append((key, ref_bop, got_bop, delta_pct, status))

    new_keys = sorted(set(measured) - set(baseline.get("benchmarks", {})))

    lines = [
        "## Allocation gate (B/op) vs committed baseline",
        "",
        f"Baseline: `{args.baseline}` (tolerance: {tol_pct}% / {tol_abs} B"
        + (", **provisional**" if baseline.get("provisional") else "") + ")",
        "",
        "| benchmark | baseline B/op | measured B/op | delta | status |",
        "|---|---:|---:|---:|---|",
    ]
    for key, ref_bop, got_bop, delta_pct, status in rows:
        if got_bop is None:
            lines.append(f"| `{key}` | {fmt_bytes(ref_bop)} | - | - | {status} |")
        else:
            lines.append(f"| `{key}` | {fmt_bytes(ref_bop)} | {fmt_bytes(got_bop)} "
                         f"| {delta_pct:+.1f}% | {status} |")
    for key in new_keys:
        lines.append(f"| `{key}` | - | {fmt_bytes(measured[key]['b_op'])} | - | NEW (not in baseline) |")
    markdown = "\n".join(lines) + "\n"

    print(markdown)
    if args.markdown:
        os.makedirs(os.path.dirname(os.path.abspath(args.markdown)), exist_ok=True)
        with open(args.markdown, "w") as f:
            f.write(markdown)

    for key, ref_bop, got_bop, delta_pct in regressions:
        message = (f"alloc regression: {key} {fmt_bytes(ref_bop)} -> {fmt_bytes(got_bop)} B/op "
                   f"({delta_pct:+.1f}%); update baselines/alloc-baseline.json in this PR if intended")
        if on_azure:
            print(f"##vso[task.logissue type=warning]{message}")
        else:
            print(f"WARNING: {message}", file=sys.stderr)

    # A baselined benchmark missing from the record means it was renamed/removed; without this
    # it would silently leave the gate (a rename otherwise escapes the contract entirely).
    for key in missing:
        message = (f"alloc baseline entry missing from run: {key}; if the benchmark was renamed or "
                   f"removed, regenerate baselines/alloc-baseline.json in this PR")
        if on_azure:
            print(f"##vso[task.logissue type=warning]{message}")
        else:
            print(f"WARNING: {message}", file=sys.stderr)

    if regressions or missing:
        problems = []
        if regressions:
            problems.append(f"{len(regressions)} allocation regression(s)")
        if missing:
            problems.append(f"{len(missing)} missing baseline entry(ies)")
        print(f"\nallocation gate: FAIL ({', '.join(problems)})", file=sys.stderr)
        return 1
    print("\nallocation gate: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
