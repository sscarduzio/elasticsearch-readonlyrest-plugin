# benchmarks

JMH micro-benchmarks for `core` ACL hot paths.

The benchmarks run against the **real** production classes (e.g. `GlobPatternsMatcher` via the
public `PatternsMatcher.create` factory), so the numbers reflect the actual code, not a re-implementation.

## Running

```bash
# quick run, all benchmarks (2 forks, 5 warmup + 5 measurement iterations)
./gradlew :benchmarks:jmh

# run a subset and tune JMH options via -PjmhArgs (a single quoted string of JMH CLI args)
./gradlew :benchmarks:jmh -PjmhArgs="GlobPatternsMatcher -f 2 -wi 5 -i 5"

# include the GC profiler to see allocation rate (B/op) — the metric most affected
# by the array+while vs Vector+exists(closure) change
./gradlew :benchmarks:jmh -PjmhArgs="GlobPatternsMatcher -prof gc"
```

`-PjmhArgs` accepts any [JMH CLI options](https://github.com/openjdk/jmh): a benchmark-name
regex, `-f` forks, `-wi`/`-i` warmup/measurement iterations, `-prof gc|stack|...`, `-rf json -rff out.json`, etc.

## How it is wired

This module deliberately uses **plain `JavaExec`/`JavaCompile` tasks** instead of a third-party JMH
Gradle plugin:

1. `jmhGenerate` runs JMH's ASM bytecode generator over the compiled `@Benchmark` classes
   (works with Scala bytecode) to produce the JMH infrastructure.
2. `jmhCompileGenerated` compiles the generated Java.
3. `jmh` runs `org.openjdk.jmh.Main`.

Because nothing here applies a Gradle plugin, having this module in the build cannot affect
configuration of the rest of the project — any issue only surfaces when `:benchmarks:jmh` is run.

## Benchmarks

- `GlobPatternsMatcherBenchmark` — single-candidate `match` and bulk `filter` for small/large
  allowed-pattern sets, case-sensitive and case-insensitive. Targets the inner match-loop
  rewrite (`Array` + `while` instead of `Vector` + `exists(closure)`).

- `RuleStaticResolutionBenchmark` — the per-request work for a **statically configured**
  `DataStreamsRule`/`RepositoriesRule`/`SnapshotsRule`: the old path
  (`resolveAll(...).toCovariantSet` + `PatternsMatcher.create` on every request) vs the new
  path (reuse the matcher precomputed once at rule construction). Quantifies the
  `resolveAll`-bypass optimization. Run with `-prof gc` to see the per-request allocation
  removed on the static path.

- `HeaderRuleMatchBenchmark` — the per-request `headers_and`/`headers_or` matching cost: the old
  path (`PatternsMatcher.create(value :: Nil)` per requirement×header comparison) vs the new path
  (value matchers compiled once at `BaseHeaderRule` construction). Few- and many-requirement
  scenarios show the win scales with the number of configured headers; it is primarily an
  allocation reduction (~50–58% B/op) with a modest time gain (`-prof gc` shows the allocation
  removed).

- `AclEvaluationBenchmark` — end-to-end evaluation of 10 blocks × 4 cheap sync rules through four
  evaluators over identical inputs: the production path (incl. its `doPrivileged` async hop), the
  same production scaffolding measured synchronously, a replica of the pre-short-circuit folds,
  and a hand-rolled tail-recursive evaluator without WriterT (the upper bound of a WriterT
  removal). All evaluators are asserted to agree on the decision in `@Setup`.

- `IndicesRuleResolutionBenchmark` — the per-request cost the `AllowedClusterIndices` holder
  removes for a statically configured `IndicesRule`: `resolveAll` + local/remote split + glob
  compilation per request vs a single match against the precomputed matchers.

- `JwtParserBenchmark` — `Jwts.parser().verifyWith(...).build()` per request (the old
  `BaseJwtRule`/`BaseRorKbnRule` behavior) vs a parser prebuilt on the shared definition, for
  HMAC-SHA256 and RSA-2048. The newPath numbers document the per-verification floor.

- `HeaderNameEqBenchmark` — the case-insensitive `Header.Name` Eq before (lowercase both sides
  per comparison) vs after (precomputed `lowerCased` field), over a 20-header scan.

- `EagerHashCodeBenchmark` — what `EagerHashCode` on `RequestedIndex` buys: `Set.contains` with
  reused instances (cached hash amortized) vs an identically shaped plain case class, plus a
  create-then-lookup variant showing the eager-at-construction cost.

- `BasicAuthDecodeBenchmark` — the `basicAuth` header scan + Base64 decode run once per
  basic-auth block (old extension def, 5 blocks modeled) vs once per request (lazy val).

> **Note:** the JMH source generator (`jmhGenerate`) does not clean its output between runs, so
> after renaming/removing a `@Benchmark` method run `./gradlew :benchmarks:clean` once before
> `:benchmarks:jmh`.
