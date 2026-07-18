# benchmarks

JMH micro-benchmarks for `core` ACL hot paths, with a reviewed KPI manifest (`kpis.yml`).

The benchmarks run against the **real** production entry points (`EnabledAccessControlList
.handleRegularRequest`, `rule.check`, `PatternsMatcher.create`, ...), so the numbers reflect the
actual code, not a re-implementation. They are **absolute KPI benchmarks**: they measure whatever
the current code does, which is exactly what makes optimizations (and regressions) visible when
runs are compared.

This module is **manually-run tooling** — it is not wired into any CI gate. (An automated
version was built and shelved; see *The automated version (shelved)* below.)

## Running

```bash
# quick run, all benchmarks (2 forks, 5 warmup + 5 measurement iterations)
./gradlew :benchmarks:jmh

# run a subset and tune JMH options via -PjmhArgs (a single quoted string of JMH CLI args)
./gradlew :benchmarks:jmh -PjmhArgs="GlobPatternsMatcher -f 2 -wi 5 -i 5"

# include the GC profiler to see allocation rate (B/op)
./gradlew :benchmarks:jmh -PjmhArgs="ActionsRule -prof gc"

# the smoke subset (the tier-1 KPIs from kpis.yml, at canonical sizes, with -prof gc):
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
│   └── support/   BenchmarkSupport (request/ES-stub scaffolding, production types only),
│                  BenchmarkAclUtils (shared ACL-object creation + assertion helpers)
└── kpis.yml       # the elected KPIs — the reviewed contract of what we track (21 KPI ids);
                   # jmhSmoke runs its tier-1 entries, verifyKpis guards it against renames
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

## Development process

How this module plugs into day-to-day work (deliberately lightweight — no CI gate, a habit
plus one guard task):

1. **When a PR touches a `core` ACL hot path** (rules, matchers, ACL evaluation, request
   context) — or claims a perf win — run the smoke subset on both sides:

   ```bash
   git checkout develop        && ./gradlew :benchmarks:jmhSmoke   # save the table
   git checkout <your-branch>  && ./gradlew :benchmarks:jmhSmoke
   ```

   Paste the before/after `score` and `gc.alloc.rate.norm` (B/op) columns into the PR
   description. B/op is deterministic enough to compare across machines; time (us/op) only on
   the same quiet machine.

2. **Reviewer side:** on a perf-relevant PR with no numbers, ask for them. That ask is the
   entire enforcement mechanism today.

3. **Adding/renaming a benchmark:** update `kpis.yml` in the same commit (tier-1 entries are
   automatically part of the smoke subset). `./gradlew :benchmarks:verifyKpis` (also run
   automatically by `jmhSmoke`) fails on any manifest entry that no longer resolves to a
   compiled `@Benchmark` method — the manifest can't silently rot.

4. **Optimization PRs** may carry `oldPath_*` replica methods as review evidence; prune them
   at merge (see Conventions).

5. **Escalation:** if perf-relevant PRs become frequent enough that the manual habit strains,
   revive the shelved automated version (see below) — preferring the existing
   Grafana/Prometheus stack over its custom storage.

## Conventions

- **Old-path replicas live only while their PR is open.** A PR that optimizes a hot path may
  add `oldPath_*` replica methods as review evidence, but they are pruned when the PR merges.
- KPI benchmarks pin the canonical size at field max (~100 blocks / ~100 groups); `@Param`
  axes around it make scaling curvature visible without exponent series.
- Blocks are built with the production `RuleOrdering` (`rules.sorted`), and the indices KPI has
  both a concrete-name variant and a wildcard-expansion variant (the wildcard path is the
  production hot path for `logs-*`-style requests).

## The automated version (shelved)

We already built an automated version of this: a CI gate that compared allocation numbers on
every PR, plus a nightly run that recorded all KPIs over time and raised an alert when a number
drifted. It is not in use. The code lives on the `parked/perf-nightly-tools` branch.

Why it is shelved:

- The nightly run needs a dedicated, always-identical machine to produce comparable numbers,
  and we do not have one.
- A design review found that its alerting logic could not be trusted yet: a slow regression
  would gradually become the "normal" baseline, and a benchmark rename silently restarted its
  history — so a real regression could pass unnoticed.

What has to happen before anyone revives it:

1. Set up a dedicated benchmark machine.
2. Fix the alerting logic on the parked branch (the review findings are documented there).
3. First check whether the existing Grafana/Prometheus stack can store and chart the KPI
   series — that would replace the custom storage and dashboard the parked branch carries.

## How it is wired

This module deliberately uses **plain `JavaExec`/`JavaCompile` tasks** instead of a third-party
JMH Gradle plugin:

1. `jmhGenerate` runs JMH's ASM bytecode generator over the compiled `@Benchmark` classes
   (works with Scala bytecode) to produce the JMH infrastructure.
2. `jmhCompileGenerated` compiles the generated Java.
3. `jmh` runs `org.openjdk.jmh.Main`; `jmhSmoke` runs the tier-1 KPI subset (parsed from
   `kpis.yml`) with `-prof gc` and writes raw JSON to `build/jmh/smoke-raw.json`.

Because nothing here applies a Gradle plugin, having this module in the build cannot affect
configuration of the rest of the project — any issue only surfaces when the tasks are run.

> **Note:** the JMH source generator (`jmhGenerate`) does not clean its output between runs, so
> after renaming/removing a `@Benchmark` method run `./gradlew :benchmarks:clean` once before
> `:benchmarks:jmh`.
