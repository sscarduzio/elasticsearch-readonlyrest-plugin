---
name: ror-release
description: ReadonlyREST versioning, branching, and release mechanics — pluginVersion vs publishedPluginVersion, S3 artifact pipeline, supporting a new ES version, unstable customer builds. Use when bumping versions, adding ES version support, releasing, or producing a build for a customer.
---

# ROR Versioning & Release

Source: `beshu-tech/readonlyrest-internal` (`versioning.md`, `releasing.md`), reconciled with the current repo where the docs are stale.

## Versioning rules (binding)

- Stable versions are semver `X.Y.Z`; unstable are `X.Y.Z-preN`. Both live in `/gradle.properties` → `pluginVersion`.
- ES and Kibana plugins are released **in lockstep**: customers must install the same ROR version on both sides. Never release one without the other.
- `master` holds **stable versions only** — a `-pre` version on master breaks CI. `develop` takes `-pre` versions; PRs are squash-and-merged into it.

## pluginVersion vs publishedPluginVersion (the iron rule)

`gradle.properties` has two keys:

- `pluginVersion` — what CI builds when the value changes in a commit.
- `publishedPluginVersion` — what readonlyrest.com/download advertises (read directly from git).

**Order**: bump `pluginVersion` → wait until CI has uploaded ALL deliverables to S3 → only then set `publishedPluginVersion` to match. Flipping `publishedPluginVersion` early advertises downloads that don't exist yet.

## CI artifact pipeline

- Committing a `pluginVersion` change triggers the GitHub Actions release jobs: one zip per supported ES version, uploaded to the artifacts store under `builds/`.
- Each uploaded deliverable gets a git tag `v<plugin_version>_es<es_version>` (e.g. `v1.37.0_es7.16.2`). CI **skips builds whose tag already exists** — so to resume a half-failed release, just re-run the pipeline; it is idempotent.
- Release closeout: update the changelog in `beshu-tech/readonlyrest-docs` (`changelog.md`) and send the Mailchimp campaign.

## Supporting a new ES version (current mechanism)

Verified against recent PRs (e.g. #1257). Files touched:

1. `es{NN}x/gradle.properties` — add the version to `supportedEsVersions` in the matching module. **This is the single source of truth**: CI build/test, the default ES module, and the docs action-string sync all derive from it (`EsModuleFinder` / `printAllSupportedEsVersions`). There is no separate version list to update.
2. Mirror the ES jars into the libs store, for versions Maven Central doesn't carry yet: run the **Mirror ES Libs** workflow (`.github/workflows/mirror-es-libs.yml`) with `es_versions` set to the new versions (e.g. `9.5.1 9.4.5`). Manual, once per version, and **before** the branch adding that version goes through CI — the build resolves ES dependencies from the store, so it cannot compile against jars that aren't there yet. Not what gates CI build/test (that's `supportedEsVersions`, step 1).
3. Sometimes `ror-tools/build.gradle`.

Test locally first: `./gradlew integration-tests:test '-PesModule=es{NN}x'`.

- **Adapt code keeping old-version compatibility at all costs.** Only when the new ES introduces true breaking changes: copy the latest module (`cp -r es93x es94x`), add it to `settings.gradle` and the files above.
- The PR must come from the main repo, **not a fork** (artifact upload needs S3 credentials).
- For unreleased/snapshot ES versions: build ES from source (`elastic/elasticsearch` repo) and publish deps to mavenLocal with `./gradlew clean publishElasticPublicationToMavenLocal` (needed jars: `elasticsearch` SDK, `transport-netty4`, `elasticsearch-plugin-classloader`).

## Unstable builds for customer support

To let a customer verify a fix mid-sprint: merge the fix PR to `develop` and bump the `-preN` number — CI uploads fresh builds to S3. Share a signed temporary link from https://download.readonlyrest.com/.

**Edition caution**: give the customer the edition they're licensed for — never hand an Enterprise build to a Free/PRO customer. If in doubt, ask Simone or Ben.
