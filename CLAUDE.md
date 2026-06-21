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
- **`integration-tests/`** — Docker-based integration tests using TestContainers. Suites run **serially within a worker JVM** (they share one mutable singleton ES, guarded by an acquire/release latch), so `maxParallelForks = 1` always (forks-as-parallelism is a confirmed dead end — Gradle's JUnit-Platform engine runs the scalatest Launcher once per worker, so `maxParallelForks`/`forkEvery` can't split it; gradle/gradle#8632). Parallelism instead comes from **suite SHARDING**: K separate `integration-tests:test` invocations (`-PshardCount=K -PshardIndex=i`), each a fresh JVM with its own singleton ES, over a disjoint name-hash partition of the suites. `IT_SHARD_COUNT` (default 1) drives it in CI; at K=1 everything runs in one JVM.
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

## Code Review

For PR review work, invoke the **`ror-pr-review`** skill (`.claude/skills/ror-pr-review/SKILL.md`). It contains the binding review methodology, repo-specific bug heuristics, project-convention checklist, and required output format.
