# benchmarks

JMH micro-benchmarks for `core` ACL hot paths, with a reviewed KPI manifest (`kpis.yml`).

The benchmarks run against the **real** production entry points (`EnabledAccessControlList
.handleRegularRequest`, `rule.check`, `PatternsMatcher.create`, ...), so the numbers reflect the
actual code, not a re-implementation. They are **absolute KPI benchmarks**: they measure whatever
the current code does, which is exactly what makes optimizations (and regressions) visible when
runs are compared.

This module is **manually-run tooling** — it is not wired into any CI gate. (A PR gate + nightly
time-series design was prototyped and parked; see *Parked CI legs* below.)

## Running

```bash
# quick run, all benchmarks (2 forks, 5 warmup + 5 measurement iterations)
./gradlew :benchmarks:jmh

# run a subset and tune JMH options via -PjmhArgs (a single quoted string of JMH CLI args)
./gradlew :benchmarks:jmh -PjmhArgs="GlobPatternsMatcher -f 2 -wi 5 -i 5"

# include the GC profiler to see allocation rate (B/op)
./gradlew :benchmarks:jmh -PjmhArgs="ActionsRule -prof gc"

# the smoke subset (ci-smoke.txt regexes at canonical KPI sizes, with -prof gc):
# the recommended quick before/after check for a perf-relevant PR (~5-8 min)
./gradlew :benchmarks:jmhSmoke
```

`-PjmhArgs` accepts any [JMH CLI options](https://github.com/openjdk/jmh): a benchmark-name
regex, `-f` forks, `-wi`/`-i` warmup/measurement iterations, `-prof gc|stack|...`,
`-rf json -rff out.json`, `-p param=value`, etc.

**Comparing runs:** run `jmhSmoke` (or `jmh` with `-rf json`) on the base branch and on your
branch, and diff the reported score / `gc.alloc.rate.norm` (B/op) columns. B/op is the most
stable metric across machines; wall-clock time is only comparable on the same quiet machine.

## Module layout

```
benchmarks/
├── src/main/scala/tech/beshu/ror/benchmarks/
│   ├── acl/       AclEvaluationBenchmark (blocks @Param), EnterpriseScenarioBenchmark
│   ├── rules/     IndicesRuleResolution (patterns/requestedIndices @Param + wildcard-expansion
│   │              variant), GroupsRule, ActionsRule, RuleStaticResolution, HeaderRuleMatch,
│   │              JwtVerification
│   ├── matchers/  GlobPatternsMatcher
│   ├── domain/    HeaderNameEq (production Set[Header].find), BasicAuthDecode
│   └── support/   BenchmarkSupport (request/ES-stub scaffolding, production types only)
├── kpis.yml       # the elected KPIs — the reviewed contract of what we track (21 KPI ids)
└── ci-smoke.txt   # JMH include regexes for the smoke subset
```

## The KPI manifest

`kpis.yml` elects the tracked KPIs in two tiers:

- **Tier 1 (product KPIs)**: end-to-end ACL evaluation at field-max sizes (~100 blocks,
  ~100 groups — the realistic worst case reported by the field), the composite enterprise
  scenario, indices/groups rule checks, JWT verification.
- **Tier 2 (micro KPIs)**: glob matching, header-name Eq, header rules, basic-auth decode,
  static resolution, actions rule — they explain tier-1 inflections.

New benchmarks should be added to `kpis.yml` so the manifest stays the single reviewed list of
what matters.

## Conventions

- **Old-path replicas live only while their PR is open.** A PR that optimizes a hot path may
  add `oldPath_*` replica methods as review evidence, but they are pruned when the PR merges.
- KPI benchmarks pin the canonical size at field max (~100 blocks / ~100 groups); `@Param`
  axes around it make scaling curvature visible without exponent series.
- Blocks are built with the production `RuleOrdering` (`rules.sorted`), and the indices KPI has
  both a concrete-name variant and a wildcard-expansion variant (the wildcard path is the
  production hot path for `logs-*`-style requests).

## Parked CI legs

A full perf-regression CI design (PR-level advisory allocation gate, nightly full runs on a
pinned self-hosted agent, git-orphan-branch time series, MAD judge, static dashboard) was
implemented and then **parked** on the `parked/perf-nightly-tools` branch: it depends on a
pinned self-hosted agent that does not currently exist, and an adversarial design review found
the judge/baseline mechanics need rework (rolling-median self-normalization, series-reset
laundering, silent alert chain) before the signal is trustworthy.

Before reviving it: seed a pinned agent, fix the judge findings on that branch, and first
evaluate pushing the KPI series into the existing Grafana/Prometheus stack instead of the
bespoke git-DB + dashboard.

## How it is wired

This module deliberately uses **plain `JavaExec`/`JavaCompile` tasks** instead of a third-party
JMH Gradle plugin:

1. `jmhGenerate` runs JMH's ASM bytecode generator over the compiled `@Benchmark` classes
   (works with Scala bytecode) to produce the JMH infrastructure.
2. `jmhCompileGenerated` compiles the generated Java.
3. `jmh` runs `org.openjdk.jmh.Main`; `jmhSmoke` runs the `ci-smoke.txt` subset with `-prof gc`
   and writes raw JSON to `build/jmh/smoke-raw.json`.

Because nothing here applies a Gradle plugin, having this module in the build cannot affect
configuration of the rest of the project — any issue only surfaces when the tasks are run.

> **Note:** the JMH source generator (`jmhGenerate`) does not clean its output between runs, so
> after renaming/removing a `@Benchmark` method run `./gradlew :benchmarks:clean` once before
> `:benchmarks:jmh`.
