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
| `e2e_prepare` | resolves the e2e matrix + starts the ROR KBN image build | pushes + PRs (not drafts) |
| `e2e_tests` | Cypress e2e suite, one job per ES version | pushes + PRs (not drafts) |
| `build_ror` | builds all plugin zips + bytecode-reuse guard | PRs |
| `build_toolchains_image` | rebuilds the toolchains image | weekly cron + manual |
| `determine_ci_type` → `upload_pre_ror` / `release_ror` / `publish_mvn` | release pipeline | develop/master pushes + manual `release_without_testing` |

Manual actions (`workflow_dispatch` → `actionToPerform`): `run_all_tests_on_linux`,
`run_all_tests_on_windows`, `run_e2e_tests`, `build_toolchains_image`,
`release_without_testing`.

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

## E2E tests

The Cypress suite runs the whole stack: Elasticsearch and Kibana, with both ROR plugins, in
docker. The logic is in `ci/e2e-tests-lib.sh`; the suite itself lives in the
[`readonlyrest-e2e-tests`](https://github.com/beshu-tech/readonlyrest-e2e-tests) repo and is cloned
at run time.

Three repos take part, so no single one owns the whole thing: this repo builds the ROR ES image,
`readonlyrest_kbn` builds the ROR KBN image, and the e2e repo owns the suite, the runner, and the
contract between all three —
[`ci/prebuild-images-lib.sh`](https://github.com/beshu-tech/readonlyrest-e2e-tests/blob/develop/ci/prebuild-images-lib.sh)
(image names, tag shape, workflow inputs, wait/poll behaviour). Change the contract there, not here.

Two jobs, because the two plugin images are built in different places:

- `e2e_prepare` runs once. It resolves the newest ES version of every module in the matrix
  (`:esXXXx:printNewestEsVersionForModule`), publishes that matrix, and dispatches **one** ROR
  KBN image build for all those versions. Non-blocking: it does not wait for the build.
- `e2e_tests` runs once per version, in parallel. It builds and pushes the ROR ES dev image from
  **this** commit, waits for the ROR KBN image, then runs the suite against both.

Both sides address the images by a per-run tag (`run-<build id>`), and the build id is created by
`e2e_prepare` and passed down as a job output. A test job must never re-derive it: a partial re-run
bumps the run attempt without re-running `e2e_prepare`, and the two sides would then name different
images.

Branch resolution — both other repos are asked for the branch of this PR first. The e2e clone then
tries the base branch, `develop`, `master`; the base branch matters, because a change based on
`develop` must use the `develop` suite, not `master`. The ROR KBN branch name is passed through as
it is: if the KBN repo has no such branch, its pre-build workflow falls back to `develop` on its
own side.

The matrix is three modules (newest 9.x, 8.x, 7.x), empty for draft PRs. Job names are built from
the module, not the ES version, so branch-protection checks survive a version bump. The same
`<leg>_<module>` shape is used by all three test families: `it_linux_es94x`, `it_win_es94x`,
`e2e_es94x`.

On failure the job uploads the Cypress videos and screenshots to the E2E_REPORTS store
(`ci/upload-cypress-artifacts-to-s3.sh`), under `<prefix>/<date>/build_<run id>/<module>_<elk>/`.
S3, not GitHub artifacts: those count against the metered Actions-storage quota.

Needs `ROR_ACTIVATION_KEY` (the suite refuses to start without it) and `KBN_REPO_GH_TOKEN` (to
dispatch the ROR KBN build and read its status).

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
| `E2E_REPORTS` | Cypress videos + screenshots of failed e2e runs (`e2e_reports/`) | `e2e_tests`, on failure only |

The LIBS store is the one with two sides: every plugin build **reads** it as a Maven repository,
and `mirror-es-libs.yml` **writes** it. Both take its address from `LibsStore` (build-base) so
they cannot name different locations — when they did, the symptom was an unresolvable
`org.elasticsearch:elasticsearch:X.Y.Z` at compile time, nowhere near the cause.

Mirroring is manual and one-off per ES version: run **Mirror ES Libs** with `es_versions` set
(e.g. `9.5.1 9.4.5`), and run it **before** the branch adding that version goes through CI — the
build cannot compile against jars that are not in the store yet.

## Secrets & variables

**21 repository secrets + 12 variables**:

| Secret | Purpose |
|---|---|
| `ROR_LIBS_STORE_ACCESS_KEY_ID` / `..._SECRET` | libs S3 bucket (shared ES jars; read by every build, written by `mirror-es-libs.yml`) |
| `ROR_ARTIFACTS_STORE_ACCESS_KEY_ID` / `..._SECRET` | artifacts S3 bucket (built plugin binaries) |
| `ROR_E2E_REPORTS_STORE_ACCESS_KEY_ID` / `..._SECRET` | e2e reports S3 bucket (Cypress artifacts of failed runs). Own key pair on purpose: it must not be able to write to the customer-facing `builds/` tree |
| `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_PASSWORD` | pushing ROR + toolchains images |
| `ROR_ACTIVATION_KEY` | ROR PRO/Enterprise key the e2e stack boots with |
| `KBN_REPO_GH_TOKEN` | dispatches the ROR KBN image build and reads its run status |
| `DOCKER_HUB_USER` / `DOCKER_HUB_RO_TOKEN` | authenticated docker pulls (testcontainers rate limit); `ci/docker-hub-auth.sh` is a no-op when unset |
| `NVD_API_KEY`, `OSS_INDEX_USERNAME`, `OSS_INDEX_PASSWORD` | `cve_check` feeds |
| `MAVEN_REPO_USER`, `MAVEN_REPO_PASSWORD`, `MAVEN_STAGING_PROFILE_ID`, `GPG_KEY_ID`, `GPG_PASSPHRASE` | Maven Central publishing |
| `PGP_SECRET_KEY_B64` | base64 of `secret.pgp`; the publish step decodes it to `.travis/secret.pgp`. Create with `base64 -w0 secret.pgp \| gh secret set PGP_SECRET_KEY_B64` |

Variables (`vars.NAME`, non-sensitive): `ROR_LIBS_STORE_{ENDPOINT_URL,REGION,BUCKET,PATH_PREFIX}`,
`ROR_ARTIFACTS_STORE_{...}` and `ROR_E2E_REPORTS_STORE_{...}`. Only the key pairs are secrets. The
E2E_REPORTS non-key values may be set as variables or as secrets — the workflow reads `vars` first
and falls back to `secrets`. The LIBS values are optional —
unset (or empty, which is what an undefined `vars.X` expands to) falls back to the defaults in
`LibsStore`, which is also what a local build resolves against.

Release tags push via the checkout token (`release_ror` has `permissions: contents: write`)
— no SSH deploy key. Fork PRs get no secrets (GitHub default); `cve_check` and docker auth
degrade instead of failing.
