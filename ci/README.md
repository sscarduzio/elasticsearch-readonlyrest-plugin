# ReadonlyREST CI

CI runs on GitHub Actions: `.github/workflows/ci.yml`. Linux jobs run on **Ubicloud**
runners (`ubicloud-standard-4` = 4 vCPU / 16 GB) inside the `beshultd/ror-ci-toolchains`
image; Windows jobs run on GitHub-hosted `windows-2025`.

Every Linux job calls `ci/run-pipeline.sh` with a `ROR_TASK` — the scripts in this
directory contain the build logic; the workflow only orchestrates.

## Jobs

| Job | What it does | When |
|---|---|---|
| `setup` | computes branch flags + the IT matrices | always |
| `toolchains_verify` | sanity-checks the toolchains image | always (fail-fast gate for tests) |
| `required_checks` | audit build, cross-Scala compile, format, license | pushes + PRs |
| `unit_tests_linux` | `core:test` and friends | pushes + PRs |
| `optional_checks` | non-blocking checks (matrix; today: `cve_check` OWASP dependency-check, needs `NVD_API_KEY`) — failures annotate the run but never block it | pushes + PRs |
| `it_linux` | integration tests, one job per ES version | 10-version subset on PRs, full 34 on develop/master/epic and manual |
| `it_windows` | integration tests on native-Windows ES | 3 on PRs, 7 on branches, full 33 on manual |
| `unit_tests_windows` | `core:test` on Windows | manual `run_all_tests_on_windows` |
| `build_ror` | builds all plugin zips + bytecode-reuse guard | PRs |
| `build_toolchains_image` | rebuilds the toolchains image | weekly cron + manual |
| `determine_ci_type` → `upload_pre_ror` / `release_ror` / `publish_mvn` | release pipeline | develop/master pushes + manual `release_without_testing` |
| `disk_probe` | host-disk recon | manual `run_disk_probe` |

Manual actions (`workflow_dispatch` → `actionToPerform`): `run_all_tests_on_linux`,
`run_all_tests_on_windows`, `build_toolchains_image`, `release_without_testing`,
`run_disk_probe`.

Other workflows in `.github/workflows/`, all manual or event-driven and independent of the
above: `mirror-es-libs.yml` (mirrors ES jars into the libs store — see [S3 stores](#s3-stores)),
`pr-conventions.yml` (PR title/changelog checks), `actionstrings_gen.yml` (regenerates the ES
action-string lists in the docs repo), `publish-pre-builds.yml` (on-demand ROR+ES dev images).

Two orchestration rules worth knowing before editing conditions:

- `concurrency` auto-cancels superseded **PR** runs only; branch pushes queue, so a push
  during a release run can never kill the release.
- GitHub skips a job whose `needs` contains a skipped job. The release jobs therefore use
  `!cancelled()` + explicit `needs.<job>.result` checks — that is what makes the manual
  `release_without_testing` path (tests intentionally skipped) work. Keep that pattern.

## Integration-test parallelism

Each IT leg runs **4 sharded test JVMs** on its VM (Windows: 3), orchestrated by
`integration-tests:shardedTest` (`IT_PARALLELISM` → `-PshardCount`). Suites are
partitioned by `SuiteSharder` (build-base; unit-tested), packed by measured duration
(`integration-tests/suite-timings.json`, `ROR_BALANCED_SHARDS`) so no shard becomes the
long pole. Two things make this fit a 16 GB box:

- **Heavy-suite gate** (`ROR_HEAVY_SUITE_PERMITS`, currently 2): a machine-wide
  `FileLockSemaphore` capping how many multi-node-cluster suites boot containers
  concurrently across the shard JVMs. Without it, level packing OOMs the host. Crash-safe:
  a killed worker's lock dies with its process.
- On Windows (native ES processes, no docker), every shard gets its own port window and
  install dirs (`RorShard`).

Suite timing drift is auto-detected: the es90x leg (first ES 9 module — a stable reference) runs
`integration-tests:regenerateSuiteTimings` after the tests, warns on drift, and uploads a
regenerated `suite-timings.json` as the `suite-timings-regenerated` artifact — to update,
download it and commit. Measured tuning limits (don't re-learn them the hard way): 5 shard
JVMs or 3 gate permits exceed either 16 GB or the 4-vCPU boot-time budget.

Shard stdout is written to `shard-<i>.log` files (live interleaving would be unreadable),
printed to the job console afterwards as collapsible groups, and uploaded as the
`sharded-logs-*` artifact. Per-shard JUnit XML uploads as `*-results`.

## S3 stores

ROR uploads to S3-compatible stores through one path: `ci/s3-uploader.sh` (curl + SigV4) under
`ci-lib.sh`'s `upload_using_aws_s3_uploader` / `..._to_key`, driven by `ci/upload-files-to-s3.sh`.
A store is named, and its name selects a family of env vars — `ROR_<STORE>_STORE_{ENDPOINT_URL,
ACCESS_KEY_ID,ACCESS_KEY_SECRET,BUCKET,REGION,PATH_PREFIX}`. Resolve that family in one place
(`ci-lib.sh`) and nowhere else; each store has its own credentials, because the gateway authorizes
each prefix separately.

| Store | Holds | Written by |
|---|---|---|
| `ARTIFACTS` | the plugin zips customers download (`builds/`) | `upload_pre_ror` / `release_ror` |
| `LIBS` | ES jars + POMs mirrored for versions not yet on Maven Central (`libs/`) | the `mirror-es-libs.yml` workflow |

The LIBS store is the one with two sides: every plugin build **reads** it as a Maven repository,
and `mirror-es-libs.yml` **writes** it. Both take its address from `LibsStore` (build-base) so
they cannot name different locations — when they did, the symptom was an unresolvable
`org.elasticsearch:elasticsearch:X.Y.Z` at compile time, nowhere near the cause.

Mirroring is manual and one-off per ES version: run **Mirror ES Libs** with `es_versions` set
(e.g. `9.5.1 9.4.5`), and run it **before** the branch adding that version goes through CI — the
build cannot compile against jars that are not in the store yet. It used to be a CI job
(`es_s3_up`) fired by pushing a `newes/*` branch; that coupled a few-times-a-year manual act to
every push on such a branch, and made five CI jobs depend on a job that almost always did nothing.

## Secrets & variables

**17 repository secrets + 8 variables**:

| Secret | Purpose |
|---|---|
| `ROR_LIBS_STORE_ACCESS_KEY_ID` / `..._SECRET` | libs S3 bucket (shared ES jars; read by every build, written by `mirror-es-libs.yml`) |
| `ROR_ARTIFACTS_STORE_ACCESS_KEY_ID` / `..._SECRET` | artifacts S3 bucket (built plugin binaries) |
| `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_PASSWORD` | pushing ROR + toolchains images |
| `DOCKER_HUB_USER` / `DOCKER_HUB_RO_TOKEN` | authenticated docker pulls (testcontainers rate limit); `ci/docker-hub-auth.sh` is a no-op when unset |
| `NVD_API_KEY`, `OSS_INDEX_USERNAME`, `OSS_INDEX_PASSWORD` | `cve_check` feeds |
| `MAVEN_REPO_USER`, `MAVEN_REPO_PASSWORD`, `MAVEN_STAGING_PROFILE_ID`, `GPG_KEY_ID`, `GPG_PASSPHRASE` | Maven Central publishing |
| `PGP_SECRET_KEY_B64` | base64 of `secret.pgp`; the publish step decodes it to `.travis/secret.pgp`. Create with `base64 -w0 secret.pgp \| gh secret set PGP_SECRET_KEY_B64` |

Variables (`vars.NAME`, non-sensitive): `ROR_LIBS_STORE_{ENDPOINT_URL,REGION,BUCKET,PATH_PREFIX}`
and `ROR_ARTIFACTS_STORE_{...}`. Only the key pairs are secrets. The LIBS values are optional —
unset (or empty, which is what an undefined `vars.X` expands to) falls back to the defaults in
`LibsStore`, which is also what a local build resolves against.

Release tags push via the checkout token (`release_ror` has `permissions: contents: write`)
— no SSH deploy key. Fork PRs get no secrets (GitHub default); `cve_check` and docker auth
degrade instead of failing.

## Coverage: what happened to every Azure stage

Everything the Azure pipeline did is ported — nothing was dropped. The mapping:

| Azure stage | GitHub Actions equivalent |
|---|---|
| `SUPERSEDE_GUARD` | native `concurrency:` block (auto-cancel stale PR runs; branch pushes queue) |
| `DETERMINE_CI_TYPE` | `setup` (branch flags, matrices) + `determine_ci_type` (release-type decision) |
| `DISK_PROBE` | `disk_probe` (manual `run_disk_probe`) |
| `BUILD_TOOLCHAINS_IMAGE` | `build_toolchains_image` (weekly cron + manual) |
| `TOOLCHAINS_VERIFY` | `toolchains_verify` |
| `ES_S3_UP` | the standalone `mirror-es-libs.yml` workflow (manual; was a `newes/*` push trigger) |
| `REQUIRED_CHECKS` | `required_checks` (same 4-task matrix) |
| `OPTIONAL_CHECKS` | `optional_checks` (matrix; `continue-on-error` = "warn but pass") |
| `TEST` (unit + IT + WIN legs) | `unit_tests_linux`, `it_linux`, `it_windows`, `unit_tests_windows` |
| `BUILD_ROR` | `build_ror` |
| `UPLOAD_PRE_ROR` | `upload_pre_ror` |
| `RELEASE_ROR` / `RELEASE_ROR_WITHOUT_TESTING` | `release_ror` (the `release_without_testing` dispatch input selects the no-test path) |
| `PUBLISH_MVN_ARTIFACTS` / `..._WITHOUT_TESTING` | `publish_mvn` (same dispatch input) |
| Azure "secure file" `secret.pgp` | `PGP_SECRET_KEY_B64` repo secret, decoded in-step |

`azure-pipelines.yml` is kept with `trigger: none` / `pr: none` as a manually-runnable
fallback for one release cycle after the switch, then it gets deleted.
