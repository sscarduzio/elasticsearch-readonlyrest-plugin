# benchmarks

JMH micro-benchmarks for `core` ACL hot paths, plus the performance-regression CI tooling
(KPI manifest, PR allocation gate, nightly time series, dashboard).

The benchmarks run against the **real** production entry points (`EnabledAccessControlList
.handleRegularRequest`, `rule.check`, `PatternsMatcher.create`, ...), so the numbers reflect the
actual code, not a re-implementation. They are **absolute KPI benchmarks**: they measure whatever
the current code does, which is exactly what makes optimizations (and regressions) visible as a
step in the time series.

## Running

```bash
# quick run, all benchmarks (2 forks, 5 warmup + 5 measurement iterations)
./gradlew :benchmarks:jmh

# run a subset and tune JMH options via -PjmhArgs (a single quoted string of JMH CLI args)
./gradlew :benchmarks:jmh -PjmhArgs="GlobPatternsMatcher -f 2 -wi 5 -i 5"

# include the GC profiler to see allocation rate (B/op)
./gradlew :benchmarks:jmh -PjmhArgs="ActionsRule -prof gc"

# the PR smoke subset + allocation gate (what CI runs on PRs touching core/ or benchmarks/)
./gradlew :benchmarks:jmhSmoke
```

`-PjmhArgs` accepts any [JMH CLI options](https://github.com/openjdk/jmh): a benchmark-name
regex, `-f` forks, `-wi`/`-i` warmup/measurement iterations, `-prof gc|stack|...`,
`-rf json -rff out.json`, `-p param=value`, etc.

## Module layout

```
benchmarks/
├── src/main/scala/tech/beshu/ror/benchmarks/
│   ├── acl/       AclEvaluationBenchmark (blocks @Param), EnterpriseScenarioBenchmark
│   ├── rules/     IndicesRuleResolution (patterns/requestedIndices @Param), GroupsRule,
│   │              ActionsRule, RuleStaticResolution, HeaderRuleMatch, JwtVerification
│   ├── matchers/  GlobPatternsMatcher
│   ├── domain/    HeaderNameEq, BasicAuthDecode
│   └── support/   BenchmarkSupport (request/ES-stub scaffolding, production types only)
├── kpis.yml                      # the elected KPIs — reviewed contract for the CI gates
├── baselines/alloc-baseline.json # committed B/op reference — the PR-level ratchet
├── ci-smoke.txt                  # JMH include regexes for the PR smoke subset (~5-8 min)
└── tools/
    ├── extract.py                # raw JMH JSON → reduced run record (~5-10 KB)
    ├── compare.py                # record vs alloc-baseline.json → pass/warn/fail + markdown
    ├── nightly_judge.py          # record vs rolling median of last 7 history records (MAD)
    └── render_dashboard.py       # history glob → static index.html (uPlot) or CSV
```

## The KPI system

`kpis.yml` elects the tracked KPIs in two tiers:

- **Tier 1 (product KPIs)**: end-to-end ACL evaluation at field-max sizes (~100 blocks,
  ~100 groups — the realistic worst case reported by the field), the composite enterprise
  scenario, indices/groups rule checks, JWT verification.
- **Tier 2 (micro KPIs)**: glob matching, header-name Eq, header rules, basic-auth decode,
  static resolution, actions rule — they explain tier-1 inflections.

Two gates use them:

1. **PR smoke (allocations, advisory)** — the `PERF_SMOKE` job runs `jmhSmoke` on PRs touching
   `core/` or `benchmarks/` and compares B/op against `baselines/alloc-baseline.json`.
   Allocations are deterministic on shared agents; time is not judged here. The job is
   currently **advisory** (`continueOnError: true` in `azure-pipelines.yml`); after the burn-in
   period flip it to blocking by removing that line. **Baseline policy**: a PR that knowingly
   changes allocation behavior regenerates the baseline in the same PR
   (`python3 tools/compare.py --record <record> --baseline baselines/alloc-baseline.json
   --write-baseline`) and the diff is reviewed like code.
2. **Nightly time series (time, single writer)** — `ci/azure-nightly-perf.yml` runs the full
   suite on ONE pinned self-hosted agent (one hardware fingerprint), commits a reduced run
   record per night to the `benchmark-history` orphan branch (one file per run, append-only,
   no merge conflicts), judges the new record against the rolling median of the last 7
   comparable records (same env fingerprint + same `benchSuiteSha`), and re-renders the static
   dashboard. PR smoke results are ephemeral pipeline artifacts — only the nightly writes.

`benchSuiteSha` (the git tree-hash of `benchmarks/src`) segments the series whenever the
benchmarks themselves change, so a suite change never masquerades as a code regression.

Rebuilding the whole series from git alone:

```bash
git clone --single-branch -b benchmark-history <repo> history
python3 benchmarks/tools/render_dashboard.py --history 'history/results/**/*.json' \
        --kpis benchmarks/kpis.yml --csv kpis.csv
```

## Conventions

- **Old-path replicas live only while their PR is open.** A PR that optimizes a hot path may
  add `oldPath_*` replica methods as review evidence, but they are pruned when the PR merges —
  from then on the committed baseline and the nightly series are the guard. (The #1260
  old-vs-new evidence classes are intentionally not part of this suite.)
- KPI benchmarks pin the canonical size at field max (~100 blocks / ~100 groups); `@Param`
  axes around it make scaling curvature visible on the dashboard without exponent series.
- New benchmarks must be wired into `kpis.yml` to be tracked; un-elected benchmarks still run
  in the nightly and land in the history records, so they can be elected retroactively.

## How it is wired

This module deliberately uses **plain `JavaExec`/`JavaCompile` tasks** instead of a third-party
JMH Gradle plugin:

1. `jmhGenerate` runs JMH's ASM bytecode generator over the compiled `@Benchmark` classes
   (works with Scala bytecode) to produce the JMH infrastructure.
2. `jmhCompileGenerated` compiles the generated Java.
3. `jmh` runs `org.openjdk.jmh.Main`; `jmhSmoke` runs the `ci-smoke.txt` subset and is
   finalized by `jmhCompare` (extract + allocation gate).

Because nothing here applies a Gradle plugin, having this module in the build cannot affect
configuration of the rest of the project — any issue only surfaces when the tasks are run.

> **Note:** the JMH source generator (`jmhGenerate`) does not clean its output between runs, so
> after renaming/removing a `@Benchmark` method run `./gradlew :benchmarks:clean` once before
> `:benchmarks:jmh`.
