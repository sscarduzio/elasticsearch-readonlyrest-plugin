#!/usr/bin/env python3
"""Render the benchmark-history records into a static dashboard (or a flat CSV).

Reads every run record matched by --history, groups the elected KPI series (tier-1 panels
first), segments series by env fingerprint x benchSuiteSha epoch, and writes a single
self-contained index.html that pulls uPlot from a CDN. `--csv` instead emits one row per
(run, KPI) - the "rebuild the series from git alone" escape hatch. Stdlib only.
"""
import argparse
import csv
import glob
import json
import os
import sys

UPLOT_CSS = "https://cdn.jsdelivr.net/npm/uplot@1.6.32/dist/uPlot.min.css"
UPLOT_JS = "https://cdn.jsdelivr.net/npm/uplot@1.6.32/dist/uPlot.iife.min.js"


def script_json(obj):
    """json.dumps for embedding inside a <script> tag: neutralise any `</` so a stray
    `</script>` in a KPI id/rationale can't terminate the tag early (kpis.yml is maintainer-
    controlled, so this is correctness, not XSS defence)."""
    return json.dumps(obj).replace("</", "<\\/")

PALETTE = ["#2965cc", "#d13913", "#29a634", "#8f398f", "#d99e0b", "#00b3a4", "#7157d9"]

HTML_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>ReadonlyREST performance KPIs</title>
<link rel="stylesheet" href="{uplot_css}">
<style>
  body {{ font: 14px/1.4 system-ui, sans-serif; margin: 24px; background: #fafafa; color: #1c2127; }}
  h1 {{ font-size: 20px; }}
  h2 {{ font-size: 16px; border-bottom: 1px solid #d3d8de; padding-bottom: 4px; margin-top: 32px; }}
  .panel {{ background: #fff; border: 1px solid #d3d8de; border-radius: 4px;
            padding: 12px 16px; margin: 16px 0; }}
  .panel .meta {{ color: #5f6b7c; font-size: 12px; margin-bottom: 8px; }}
  .legend {{ font-size: 12px; color: #5f6b7c; }}
</style>
</head>
<body>
<h1>ReadonlyREST performance KPIs</h1>
<p class="legend">{run_count} runs - series split by environment fingerprint and benchmark-suite
epoch (benchSuiteSha), so hardware or suite changes never masquerade as regressions.</p>
<div id="panels"></div>
<script src="{uplot_js}"></script>
<script>
const DATA = {data_json};
const palette = {palette_json};
const panels = document.getElementById("panels");
let tier = null;
for (const kpi of DATA.kpis) {{
  if (kpi.tier !== tier) {{
    tier = kpi.tier;
    const h = document.createElement("h2");
    h.textContent = "Tier " + tier + (tier === "1" ? " - product KPIs" : " - micro KPIs");
    panels.appendChild(h);
  }}
  const div = document.createElement("div");
  div.className = "panel";
  div.innerHTML = "<div class='meta'><b>" + kpi.id + "</b> - " + kpi.benchmark +
                  " (" + kpi.metric + ")<br>" + kpi.rationale + "</div>";
  const plotEl = document.createElement("div");
  div.appendChild(plotEl);
  panels.appendChild(div);
  const series = [{{}}];
  const data = [kpi.timestamps];
  kpi.segments.forEach((seg, i) => {{
    series.push({{ label: seg.label, stroke: palette[i % palette.length], spanGaps: false,
                   width: 1.5, points: {{ show: true, size: 5 }} }});
    data.push(seg.values);
  }});
  new uPlot({{
    width: Math.min(1100, panels.clientWidth - 40), height: 240,
    title: kpi.id,
    series: series,
    axes: [{{}}, {{ label: kpi.metric === "b_op" ? "B/op" : "us/op" }}],
  }}, data, plotEl);
}}
</script>
</body>
</html>
"""


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


def load_records(history_glob):
    records = []
    for path in sorted(glob.glob(history_glob, recursive=True)):
        try:
            with open(path) as f:
                records.append(json.load(f))
        except (json.JSONDecodeError, OSError):
            print(f"skipping unreadable record: {path}", file=sys.stderr)
    records.sort(key=lambda r: r.get("timestamp", ""))
    return records


def write_csv(records, kpis, output):
    with open(output, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp", "srcSha", "branch", "fingerprint", "benchSuiteSha",
                         "kpi", "tier", "benchmark", "metric", "value", "err"])
        for rec in records:
            for kpi in kpis:
                entry = rec.get("benchmarks", {}).get(kpi["benchmark"])
                if not entry or kpi["metric"] not in entry:
                    continue
                writer.writerow([
                    rec.get("timestamp"), rec.get("srcSha"), rec.get("branch"),
                    rec.get("env", {}).get("fingerprint"), rec.get("benchSuiteSha"),
                    kpi["id"], kpi.get("tier"), kpi["benchmark"], kpi["metric"],
                    entry[kpi["metric"]], entry.get("err"),
                ])
    print(f"wrote {output}")


def build_panels(records, kpis):
    import calendar
    import datetime as dt

    def epoch_of(ts):
        parsed = dt.datetime.strptime(ts, "%Y-%m-%dT%H:%M:%SZ")
        return calendar.timegm(parsed.timetuple())

    timestamps = [epoch_of(r["timestamp"]) for r in records]
    panels = []
    for kpi in sorted(kpis, key=lambda k: (k.get("tier", "9"), k["id"])):
        segments = {}
        for index, rec in enumerate(records):
            entry = rec.get("benchmarks", {}).get(kpi["benchmark"])
            value = entry.get(kpi["metric"]) if entry else None
            label = (f"{rec.get('env', {}).get('fingerprint', '?')} @ "
                     f"{str(rec.get('benchSuiteSha', '?'))[:8]}")
            segments.setdefault(label, [None] * len(records))
            if value is not None:
                segments[label][index] = value
        rendered = [{"label": label, "values": values}
                    for label, values in segments.items() if any(v is not None for v in values)]
        if not rendered:
            continue
        panels.append({
            "id": kpi["id"], "tier": kpi.get("tier", "?"), "benchmark": kpi["benchmark"],
            "metric": kpi["metric"], "rationale": kpi.get("rationale", ""),
            "timestamps": timestamps, "segments": rendered,
        })
    return panels


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", required=True,
                        help="glob of run records, e.g. 'results/**/*.json'")
    parser.add_argument("--kpis", required=True, help="benchmarks/kpis.yml")
    parser.add_argument("--output", default="index.html", help="dashboard file to write")
    parser.add_argument("--csv", default=None,
                        help="write a flat CSV (one row per run x KPI) instead of HTML")
    args = parser.parse_args()

    kpis = parse_kpis(args.kpis)
    records = load_records(args.history)
    if not records:
        raise SystemExit(f"no records matched {args.history}")

    if args.csv:
        write_csv(records, kpis, args.csv)
        return 0

    html = HTML_TEMPLATE.format(
        uplot_css=UPLOT_CSS,
        uplot_js=UPLOT_JS,
        run_count=len(records),
        data_json=script_json({"kpis": build_panels(records, kpis)}),
        palette_json=script_json(PALETTE),
    )
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w") as f:
        f.write(html)
    print(f"wrote {args.output} ({len(records)} runs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
