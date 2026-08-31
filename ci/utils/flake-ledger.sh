#!/bin/bash
# Flake ledger: which CI failures REPEAT, so triage targets patterns instead of single reds.
#
# Two layers, because our failures live in both:
#   JOB level  — a leg that dies before any test runs (image build, container start, host OOM,
#                runner provisioning, registry throttling). A junit-only ledger reports NOTHING for
#                these, and in Aug 2026 they were every red we chased.
#   TEST level — assertion/initialization failures, from the `TEST-*.xml` each leg uploads.
#
# A job/test failing in some runs and passing in others is a FLAKE; one that always fails is BROKEN.
# Reruns overwrite artifacts, so a leg rerun to green leaves no test-level trace; job level survives.
#
# Usage: ci/utils/flake-ledger.sh [--branch B] [--runs N] [--with-tests]
set -uo pipefail

for tool in gh python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: '$tool' is required but not on PATH"; exit 2; }
done
gh auth status >/dev/null 2>&1 || { echo "error: 'gh' is not authenticated (run: gh auth login)"; exit 2; }
# `gh run download --pattern` landed in 2.11.0.
gh_version=$(gh --version | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ -n "$gh_version" ] && [ "$(printf '%s\n2.11.0\n' "$gh_version" | sort -V | head -1)" != "2.11.0" ]; then
  echo "error: gh $gh_version is too old; 'gh run download --pattern' needs 2.11.0+"; exit 2
fi

BRANCH=""
RUNS=20
WITH_TESTS=false
while [ $# -gt 0 ]; do
  case "$1" in
    --branch)
      [ -n "${2-}" ] || { echo "error: --branch requires a value"; exit 2; }
      BRANCH="$2"; shift 2 ;;
    --runs)
      case "${2-}" in
        ''|0|*[!0-9]*) echo "error: --runs requires a positive integer"; exit 2 ;;
      esac
      RUNS="$2"; shift 2 ;;
    --with-tests) WITH_TESTS=true; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "error: unknown argument '$1'"; exit 2 ;;
  esac
done

list_args=(--workflow ci.yml --limit "$RUNS" --json databaseId)
[ -n "$BRANCH" ] && list_args+=(--branch "$BRANCH")

echo ">>> scanning the last $RUNS ci.yml runs${BRANCH:+ of $BRANCH}"
run_ids=$(gh run list "${list_args[@]}" -q '.[].databaseId') || { echo "error: cannot list runs"; exit 1; }
[ -z "$run_ids" ] && { echo "no runs found"; exit 1; }

JOBS_TSV=$(mktemp)
WORK=$(mktemp -d)
trap 'rm -rf "$JOBS_TSV" "$WORK"' EXIT

inspected=0
skipped_runs=""
for r in $run_ids; do
  # Every terminal conclusion that is not success counts as a failure: a leg that times out or whose
  # runner never starts is just as much a red as an assertion failure, and reporting only "failure"
  # would silently drop them from the ledger.
  if rows=$(gh run view "$r" --json jobs,conclusion -q \
      'if (.jobs | length) == 0 then [ "'"$r"'", "(workflow startup)", (.conclusion // "running"), "" ] | @tsv
       else .jobs[] | [ "'"$r"'", .name, (.conclusion // "running"), ([.steps[]? | select(.conclusion=="failure") | .name] | join("+")) ] | @tsv end' 2>/dev/null) \
      && [ -n "$rows" ]; then
    echo "$rows" >> "$JOBS_TSV"
    inspected=$((inspected + 1))
  else
    skipped_runs="$skipped_runs $r"
  fi
done

downloaded=0
download_failures=""
if [ "$WITH_TESTS" = true ]; then
  echo ">>> downloading junit artifacts (slow)"
  for r in $run_ids; do
    mkdir -p "$WORK/$r"
    if gh run download "$r" -D "$WORK/$r" -p "it-*-results" >/dev/null 2>&1; then
      downloaded=$((downloaded + 1))
    else
      download_failures="$download_failures $r"
    fi
  done
fi

WITH_TESTS=$WITH_TESTS INSPECTED=$inspected TOTAL=$(echo "$run_ids" | wc -w) \
SKIPPED="$skipped_runs" DOWNLOADED=$downloaded DL_FAILED="$download_failures" \
python3 - "$JOBS_TSV" "$WORK" <<'PY'
import sys, os, glob, collections, xml.etree.ElementTree as ET

jobs_tsv, work = sys.argv[1], sys.argv[2]
inspected, total = int(os.environ["INSPECTED"]), int(os.environ["TOTAL"])
skipped = os.environ["SKIPPED"].split()
with_tests = os.environ["WITH_TESTS"] == "true"

runs_seen, runs_failed = collections.defaultdict(set), collections.defaultdict(set)
steps = collections.defaultdict(collections.Counter)
for line in open(jobs_tsv):
    row = line.rstrip("\n").split("\t")
    if len(row) < 3:
        continue
    run, job, concl = row[0], row[1], row[2]
    step = row[3] if len(row) > 3 else ""
    # A run cancelled before its matrix expanded reports the literal template as the job name.
    if "${{" in job:
        continue
    # Only real outcomes go in the denominator. Counting a cancelled or unfinished run there would
    # make a job that fails whenever it completes read as a FLAKE, understating the failure rate.
    if concl in ("running", "cancelled", ""):
        continue
    runs_seen[job].add(run)
    # "cancelled" is not a flake: a push superseding an in-flight run cancels it by design.
    if concl not in ("success", "skipped", "neutral"):
        runs_failed[job].add(run)
        steps[job][step or f"(no step; job {concl})"] += 1

print(f"\n{'='*74}\nJOB-LEVEL LEDGER — {inspected} of {total} runs inspected\n{'='*74}")
if skipped:
    print(f"  INCOMPLETE: {len(skipped)} run(s) could not be read: {' '.join(skipped)}")
ranked = sorted(((len(runs_failed[j]), len(runs_seen[j]), j) for j in runs_failed), reverse=True)
if not ranked:
    print("  no job failed in the runs that were read.")
for fails, seen, job in ranked:
    print(f"  {fails:>3}/{seen:<3} {'BROKEN' if fails == seen else 'FLAKE '}  {job}")
    print(f"            failing step(s): {', '.join(f'{s} x{c}' for s, c in steps[job].most_common(3))}")

if not with_tests:
    sys.exit(0)

dl_failed = os.environ["DL_FAILED"].split()
seen, legs, runs, bad_xml = collections.defaultdict(dict), collections.defaultdict(set), set(), []
for f in glob.glob(os.path.join(work, "*", "**", "TEST-*.xml"), recursive=True):
    rel = os.path.relpath(f, work)
    run = rel.split(os.sep)[0]
    leg = next((p for p in rel.split(os.sep) if p.startswith("it-")), "?").replace("it-", "").replace("-results", "")
    runs.add(run)
    try:
        suite = ET.parse(f).getroot()
    except ET.ParseError:
        bad_xml.append(rel)   # a leg killed mid-write leaves a truncated file
        continue
    for case in suite.iter("testcase"):
        # A skipped case is not an outcome: counting it as an appearance would turn a test that
        # fails whenever it runs into a FLAKE, the same way a cancelled run would at job level.
        if case.find("skipped") is not None:
            continue
        # Full classname: two same-named tests in different packages are different tests, and
        # merging them would invent a FLAKE out of two independent results.
        name = f'{case.get("classname", "?")}.{case.get("name", "?")}'
        failed = case.find("failure") is not None or case.find("error") is not None
        seen[name][run] = seen[name].get(run, False) or failed
        if failed:
            legs[name].add(leg)

print(f"\n{'='*74}\nTEST-LEVEL LEDGER — {len(runs)} runs with artifacts, {len(seen)} distinct tests\n{'='*74}")
if dl_failed:
    print(f"  INCOMPLETE: artifacts missing/undownloadable for {len(dl_failed)} run(s): {' '.join(dl_failed)}")
if bad_xml:
    print(f"  INCOMPLETE: {len(bad_xml)} unparsable result file(s), e.g. {bad_xml[0]}")
rows = [(sum(1 for v in per.values() if v), len(per), n) for n, per in seen.items() if any(per.values())]
if not rows:
    print("  no test failure in the artifacts that were read. Reruns overwrite artifacts, so a leg")
    print("  rerun to green leaves no trace here — read the job-level ledger above.")
for fails, total_runs, name in sorted(rows, reverse=True):
    print(f"  {fails:>3}/{total_runs:<3} {'BROKEN' if fails == total_runs else 'FLAKE '}  {name}")
    print(f"            legs: {','.join(sorted(legs[name]))}")
PY
