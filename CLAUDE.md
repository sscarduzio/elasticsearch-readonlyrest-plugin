# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

ReadonlyREST is an Elasticsearch security plugin providing access control, authentication (LDAP, JWT, SAML, proxy), and field-level security. Written primarily in **Scala 3.3.7** with Java interop, it supports 30+ Elasticsearch versions (6.7.x through 9.2.x) via version-specific adapter modules.

## Build Commands

```bash
# Build plugin for a specific ES version
./gradlew clean buildRorPlugin '-PesVersion=8.19.11'

# Build plugin using the latest supported ES version
./gradlew clean buildRorPlugin

# Run ES locally with ROR for debugging (supports ES 8.x)
./gradlew clean eshome:runEs

# Unit tests (core module)
./gradlew core:test

# Run a single test class
./gradlew core:test --tests "tech.beshu.ror.unit.SomeTestClass"

# Integration tests (requires specifying an ES module)
./gradlew integration-tests:test -PesModule=es818x

# Add license headers to new files
./gradlew licenseFormatMain
./gradlew licenseFormatTest

# Clean eshome (needed when switching ES versions)
./gradlew :eshome:clean
```

**Requirements**: JDK 17+, Gradle (wrapper included). Core module compiles with JDK 11 toolchain; ES plugin modules use JDK 17 toolchain.

## Module Architecture

- **`core/`** — Shared security logic (Scala 3). Contains access control rules, settings parsing, API endpoints, field-level security, and boot/engine initialization. All ES modules depend on this.
- **`es{version}x/`** — ES version-specific adapter modules (e.g., `es818x`, `es92x`). Each adapts core logic to a specific ES version's internal APIs. Each module's `gradle.properties` defines `latestSupportedEsVersion`.
- **`audit/`** — Audit event module, cross-compiled for Scala 2.11/2.12/2.13/3.3. Published to Maven Central separately.
- **`ror-shadowed-libs/`** — Shaded dependencies (auto-relocated to `tech.beshu.ror` prefix via Shadow plugin to avoid classpath conflicts with ES internals).
- **`integration-tests/`** — Docker-based integration tests using TestContainers. Runs sequentially (`maxParallelForks=1`).
- **`tests-utils/`** — Shared test fixtures and utilities (MockRequestContext, MockEsServices, etc.).
- **`ror-tools/` / `ror-tools-core/`** — CLI tools and utilities.
- **`eshome/`** — Local ES runner for IDE debugging. Config in `eshome/config/` (elasticsearch.yml, readonlyrest.yml). Must be cleaned when switching ES versions.
- **`build-base/`** — Gradle convention plugins applied to all modules (`readonlyrest.base-common-conventions`, `readonlyrest.plugin-common-conventions`).

## Key Source Paths

Core Scala source: `core/src/main/scala/tech/beshu/ror/`
- `accesscontrol/` — Block definitions, rules, domain models, matchers, request/response handling
- `settings/` — YAML config parsing, dynamic settings reloading (from file or ES index)
- `api/` — Internal REST endpoints (`/_readonlyrest/metadata/user`, `/_readonlyrest/admin/config`, etc.)
- `boot/engines/` — Security engine initialization
- `fls/` — Field-level security
- `es/` — ES integration abstractions

ES module entry point pattern: `es{version}x/src/main/scala/tech/beshu/ror/es/ReadonlyRestPlugin.scala`

## Key Patterns & Libraries

- **Functional Scala**: Monix 3.4.1 (Task-based effects), circe 0.14.x (JSON), refined 0.11.x (validated types), enumeratum 1.9.x (sealed enums), Cats
- **Enums**: always use `enumeratum` (`EnumEntry` / `Enum[T]`), not plain sealed traits with no `findValues`
- **Effects**: all async work uses `monix.eval.Task` — never raw `Future`, never `println` (use structured logging)
- **Shadow/Shading**: all dependencies auto-relocated under `tech.beshu.ror` prefix
- **Strict compilation**: `-Xfatal-warnings` with unused imports/params/locals/privates checks — no warnings are acceptable
- **License headers**: GNU GPL v3 headers required on all source files. Pre-commit hook runs `./gradlew license --rerun-tasks` automatically
- **Internal Scala APIs**: avoid `scala.runtime.ScalaRunTime._*` and other `_`-prefixed runtime methods — they are implementation details
- **Plugin ZIP output**: `es{version}x/build/distributions/readonlyrest-{pluginVersion}_es{esVersion}.zip`

## Testing Conventions

- **Framework**: ScalaTest with `AnyWordSpec`
- **Mocks**: ScalaMock
- **Property testing**: ScalaCheck via `scalatestplus`
- **Integration tests**: TestContainers (Docker), always specify `-PesModule=esXXXx`
- Test output is verbose by default (`showStandardStreams = true`, `exceptionFormat = full`)
- Test utilities live in `tests-utils/` — use `MockRequestContext` and `MockEsServices` for unit tests, not real ES

## When Porting Changes Across ES Modules

Many ES modules share near-identical code. When changing an ES-specific module, check whether the same change applies to other `es{version}x` modules. Divergence is intentional when ES internal APIs differ between versions — porting is not always 1:1.

## Configuration Loading

Settings can be loaded from a local file (`FileSettingsSource`) or from an ES index (`IndexSettingsSource`). Dynamic reloading is triggered via `POST /_readonlyrest/admin/refreshconfig`. Test-mode config injection is available via `/_readonlyrest/admin/config/test`.

## Git Workflow

- Main branches: `master` (stable releases), `develop` (active development)
- Feature branches: `feature/RORDEV-{issue}` pattern
- PRs target `develop`

## Code Review Instructions

When invoked as an automated PR reviewer, follow this methodology rigidly. The goal is the level of insight a careful senior Scala/Elasticsearch reviewer would produce — not a rubber-stamp "No issues found."

### Mandatory methodology

1. **Read CLAUDE.md first.** Then read the PR description and any prior review comments — they often contain context, previous findings, and the contributor's responses you must not duplicate or contradict without reason.
2. **Pull the full diff and full context.** Use `gh pr diff <num>` for the diff; `gh pr view <num> --json title,body,commits,reviews` for metadata; `gh api repos/.../issues/<num>/comments` for prior discussion. Do **not** rely on a truncated diff — if the diff is large, process it in chunks but cover every file.
3. **Read the modified files in full** (not just the hunks). Bugs usually live in the lines around the diff, not inside it. Use `Read` on each changed file before commenting on it.
4. **Cross-reference call sites.** When a function/type is touched, `grep -rn` for every caller and assess whether the change is safe for each. A behavioral change that compiles is not necessarily a behavioral change that's *correct*. The Scala compiler's variance and implicit-resolution machinery hides a lot of subtle behavior — always verify the runtime semantics.
5. **Check the tests.** For every behavioral change, ask: does a test cover this? If a test claims to cover it, does the test actually verify the new behavior, or does it pass for an incidental reason (false-confidence test)? Property-based tests with overly-narrow generators are a common variant.
6. **Check CI.** Use `gh pr checks <num>` and `gh run view <run-id> --log-failed` for any failing job. A failing integration test on a single ES module is often a hint that the code change broke a real flow on that ES version.
7. **Empirically verify claims.** If the diff includes a regex, parser, encoder, or security check — run it. `node -e "/regex/.test('input')"` is fair game for regexes; `scala-cli` if available for Scala snippets. Do not approve a security-sensitive regex without trying bypasses.

### What to actively hunt for

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

### Project-convention checklist (apply mechanically)

- `Future` instead of `monix.eval.Task` in new code
- `println` / `System.out` / `System.err` instead of structured logging
- Sealed trait + case objects without `enumeratum.Enum[T]` / `EnumEntry`
- `scala.runtime.ScalaRunTime._*` or other `_`-prefixed internal Scala APIs
- Missing GNU GPL v3 license header on new `.scala` files
- `-Xfatal-warnings` violations (unused imports/params/locals/privates)
- Identical change applied to one `es{version}x` module but missed in siblings where the same code path exists
- Direct imports of un-shaded dependency packages (should go through `tech.beshu.ror` prefix)
- `Future.successful(...)` wrappers around already-synchronous values inside `Task` flows (anti-pattern: just lift directly with `Task.now`/`Task.pure`)

### Output format

Structure the review as:

1. **What I checked** — a short list (3–8 bullets) of the actual files, lines, and call sites you examined. This is your audit trail.
2. **Findings** — issues found, ordered by severity (`🚨 Critical` → `⚠️ Warning` → `Nit`). Each finding has:
   - File and line number(s)
   - One-sentence summary
   - Reproduction or evidence (a regex test, a grep result, a call site, a benchmark)
   - Concrete suggestion (with code where helpful)
3. **What looks good** — one or two sentences crediting the parts that are notably well done (good test coverage, nice refactor, useful ADR). Genuine, not boilerplate.
4. **Open questions** — anything you couldn't determine from the diff alone, phrased as a question to the contributor.

**Do not** post `"No issues found"` without backing. If after thorough investigation there genuinely are no issues, the response must enumerate what you checked and *why* each area passed.

### When the PR is small / docs-only

A 2-line README change does not need a full audit. But the **"What I checked"** section must still appear (even if it's one bullet) so a maintainer can see your scope. The threshold for the canned "No issues found" response is: only when the diff is purely additive prose and you have read it in full.
