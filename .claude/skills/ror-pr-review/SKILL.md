---
name: ror-pr-review
description: Review a ReadonlyREST pull request with the depth of a senior Scala/Elasticsearch reviewer — not a rubber-stamp. Use when invoked as an automated PR reviewer, when the user asks for a PR review on this repo, or whenever the task is "review PR #N". Covers mandatory investigation methodology, repo-specific bug heuristics (cross-ES-version drift, internal Scala runtime APIs, hot-path allocation, false-confidence tests), the project-convention checklist, and the required output format.
---

# ReadonlyREST PR Review

The goal is the level of insight a careful senior Scala/Elasticsearch reviewer would produce — not a rubber-stamp "No issues found."

## Mandatory methodology

1. **Read CLAUDE.md first.** Then read the PR description and any prior review comments — they often contain context, previous findings, and the contributor's responses you must not duplicate or contradict without reason.
2. **Pull the full diff and full context.** Use `gh pr diff <num>` for the diff; `gh pr view <num> --json title,body,commits,reviews` for metadata; `gh api repos/.../issues/<num>/comments` for prior discussion. Do **not** rely on a truncated diff — if the diff is large, process it in chunks but cover every file.
3. **Read the modified files in full** (not just the hunks). Bugs usually live in the lines around the diff, not inside it. Use `Read` on each changed file before commenting on it.
4. **Cross-reference call sites.** When a function/type is touched, `grep -rn` for every caller and assess whether the change is safe for each. A behavioral change that compiles is not necessarily a behavioral change that's *correct*. The Scala compiler's variance and implicit-resolution machinery hides a lot of subtle behavior — always verify the runtime semantics.
5. **Check the tests.** For every behavioral change, ask: does a test cover this? If a test claims to cover it, does the test actually verify the new behavior, or does it pass for an incidental reason (false-confidence test)? Property-based tests with overly-narrow generators are a common variant.
6. **Check CI.** Use `gh pr checks <num>` and `gh run view <run-id> --log-failed` for any failing job. A failing integration test on a single ES module is often a hint that the code change broke a real flow on that ES version.
7. **Empirically verify claims.** If the diff includes a regex, parser, encoder, or security check — run it. `node -e "/regex/.test('input')"` is fair game for regexes; `scala-cli` if available for Scala snippets. Do not approve a security-sensitive regex without trying bypasses.

## What to actively hunt for

Hunt for these categories on **every** PR, not just security-flagged ones:

- **Cross-ES-version drift.** This is the #1 source of bugs in this repo. The plugin supports ES 6.7 → 9.2 via 30+ `es{version}x/` adapter modules. When a change touches one module, check whether the same fix needs to apply to siblings. The `MultiGetEsRequestContext` / `MultiSearchEsRequestContext` files in particular often need identical changes across all es modules. Use `git diff --stat` to check; if the diff touches only `es814x` but the changed code exists verbatim in `es815x`, flag it.
- **Internal Scala runtime APIs.** Any usage of `scala.runtime.ScalaRunTime._*`, `_`-prefixed runtime methods, or other implementation-detail APIs is forbidden — they change silently between Scala versions. Public alternatives exist (`scala.util.hashing.MurmurHash3.productHash` for hashCode, etc.). Recent real example: PR #1247 used `ScalaRunTime._hashCode(this)` for cached case-class hashCode; fixed by extracting an `EagerHashCode` trait using `MurmurHash3.productHash`.
- **Hot-path allocation patterns.** This codebase processes ACL evaluation per-request. Watch for: `foldLeft` over an immutable `Set` (allocates O(n) intermediate Sets — use `partition` or `mutable.Set.Builder` instead); repeated `.filter(...).map(...)` chains where a single pass would do; computing `hashCode` lazily on objects appearing in high-frequency `Set`/`Map` operations.
- **Dead-code branches.** A new branch that can never fire because an earlier branch always matches first. Common pattern: a fallback strategy preempted by an unconditional default in the same caller.
- **False-confidence tests.** Tests that appear to assert the new behavior but actually pass via an incidental code path. Stress-test by mentally running the test with the *fix* reverted — does the test still pass? If yes, the test is decorative. Property-based tests with overly-narrow generators are a common offender.
- **Behavioral changes without test coverage.** Look for code that changes the shape of a response, the conditions under which a side-effect fires, or the result of a function — without a corresponding test addition.
- **Silent removals.** A condition, branch, or feature flag deleted with no replacement. Search for the deleted identifier across the codebase to confirm intent.
- **Wrong enum style.** Sealed traits with implicit case-object children but **no** `enumeratum.Enum[T]` mixin and no `findValues` — they break JSON encoders/decoders that rely on enumeratum's introspection. Always `enumeratum`.
- **Raw `Future` instead of `monix.eval.Task`.** All async work in this codebase uses `Task`. Adding a `Future`-based code path forces an awkward conversion at the boundary and breaks structured concurrency.
- **`println` / `System.out` / `System.err`.** Forbidden — use structured logging.
- **`-Xfatal-warnings` violations.** Compilation will fail anyway, but flag obvious cases: unused imports/params/locals/privates, deprecated API usage, exhaustiveness warnings on `match`.
- **Missing GNU GPL v3 license header.** Every new `.scala` file must carry it. The pre-commit hook normally catches this, but PRs from external contributors may slip through.
- **Internal-Elasticsearch-API misuse.** Anything in `org.elasticsearch.*` outside the public APIs may break between minor versions. Particularly check usages added in `es{version}x/` modules.
- **Shadow/shading boundaries.** Direct imports of un-shaded dependency packages (e.g. `com.google.gson`, `org.apache.logging`) should go through the relocated `tech.beshu.ror.*` prefix. The Shadow plugin should hide all third-party deps.
- **Unrelated changes bundled.** A PR titled "fix LDAP timeout" should not also change FLS evaluation or settings parsing. Note bundled drift in the review.
- **Domain-invariant violations.** For changes touching `kibana_access`, the fields/FLS rule, audit, or `/_readonlyrest` APIs, check the design invariants in the `ror-internals` skill (decision-tree ordering, hidden `lucene` FLS engine, 5KB audit-event cap, endpoint→action-name mapping).

## Project-convention checklist (apply mechanically)

- `Future` instead of `monix.eval.Task` in new code
- `println` / `System.out` / `System.err` instead of structured logging
- Sealed trait + case objects without `enumeratum.Enum[T]` / `EnumEntry`
- `scala.runtime.ScalaRunTime._*` or other `_`-prefixed internal Scala APIs
- Missing GNU GPL v3 license header on new `.scala` files
- `-Xfatal-warnings` violations (unused imports/params/locals/privates)
- Identical change applied to one `es{version}x` module but missed in siblings where the same code path exists
- Direct imports of un-shaded dependency packages (should go through `tech.beshu.ror` prefix)
- `Future.successful(...)` wrappers around already-synchronous values inside `Task` flows (anti-pattern: just lift directly with `Task.now`/`Task.pure`)

## Output format

**BE CONCISE.** The review is read by a busy maintainer. Every finding = what to do + why. Nothing else.

- **Findings only**, ordered by severity (`🚨 Critical` → `⚠️ Warning` → `Nit`). Each finding, max 4 lines:
  - `file:line` — imperative one-liner: what to change.
  - Why, in 1–2 sentences, with the single strongest piece of evidence (a grep hit, a failing input, a call site) — not the full investigation.
  - A code snippet ONLY when the fix isn't obvious from the one-liner.
- NO "What I checked" narrative, NO "What looks good" section, NO methodology recap, NO restating the PR description. Do the investigation; don't serialize it.
- **Open questions**: only if the answer would change a finding; max 2.
- Genuinely no issues? One line: `No issues found — verified <the 3–5 highest-risk things you checked>.` That line IS the required backing; never a bare "No issues found".
- **Hard cap: ~30 lines / ~350 words total.** With many findings, keep every Critical/Warning full and compress Nits to one line each.

## When the PR is small / docs-only

Same rules, shorter: the no-issues one-liner (with what you verified) is a complete review for a docs-only diff you read in full.
