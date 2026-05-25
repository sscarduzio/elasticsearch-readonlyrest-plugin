# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

**Requirements**: JDK 17+, Gradle (wrapper included)

## Module Architecture

- **`core/`** — Shared security logic (Scala). Contains access control rules, settings parsing, API endpoints, field-level security, and boot/engine initialization. All ES modules depend on this.
- **`es{version}x/`** — ES version-specific adapter modules (e.g., `es818x`, `es92x`). Each adapts core logic to a specific ES version's internal APIs. Each module's `gradle.properties` defines `latestSupportedEsVersion`.
- **`audit/`** — Audit event module, cross-compiled for Scala 2.11/2.12/2.13/3.3. Published to Maven Central separately.
- **`ror-shadowed-libs/`** — Shaded dependencies (auto-relocated to `tech.beshu.ror` prefix via Shadow plugin).
- **`integration-tests/`** — Docker-based integration tests using TestContainers. Runs sequentially (maxParallelForks=1).
- **`tests-utils/`** — Shared test fixtures and utilities.
- **`ror-tools/` / `ror-tools-core/`** — CLI tools and utilities.
- **`eshome/`** — Local ES runner for IDE debugging. Config in `eshome/config/` (elasticsearch.yml, readonlyrest.yml).
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

## Key Patterns

- **Functional Scala**: Monadic error handling with Monix, circe for JSON, refined types for validation, enumeratum for enums
- **Shadow/Shading**: All dependencies auto-relocated under `tech.beshu.ror` to avoid classpath conflicts with ES
- **Strict compilation**: `-Xfatal-warnings` with unused imports/params/locals checks enabled
- **License headers**: GNU GPL v3 headers required on all source files. Pre-commit hook runs `./gradlew license --rerun-tasks`
- **Plugin ZIP output**: `es{version}x/build/distributions/readonlyrest-{pluginVersion}_es{esVersion}.zip`

## When Porting Changes Across ES Modules

Many ES modules share similar code. When making changes to an ES-specific module, check if the same change needs to be applied to other `es{version}x` modules. The modules diverge based on ES internal API changes between versions, so porting is not always 1:1.

## Git Workflow

- Main branches: `master` (stable releases), `develop` (active development)
- Feature branches: `feature/RORDEV-{issue}` pattern
- PRs target `develop`

## Code Review Instructions

When performing a code review (e.g. via `/code-review`), follow these rules:

- **Log every step**: narrate what you are doing as you go — which files you are reading, what you are looking for, what you found or did not find. Do not silently skip anything.
- **Never give up silently**: if you hit an obstacle (diff too large, file unreadable, tool error, ambiguous code), say so explicitly and explain what you tried and why you could not resolve it. Do not paper over it with "No issues found."
- **Read the full diff**: use `gh pr diff <number>` to get the complete diff. If it is large, process it in chunks — do not truncate.
- **Apply project conventions**: flag deviations from the patterns in this file (enumeratum for enums, refined types, Monix, `-Xfatal-warnings` compliance, license headers, internal Scala APIs, etc.).
- **Check for duplication**: look for the same logic implemented in multiple places across modules.
- **Output format**: produce a structured report with sections per file or theme. If there are genuinely no issues, explain what you checked and why each area passed — do not just say "No issues found."
