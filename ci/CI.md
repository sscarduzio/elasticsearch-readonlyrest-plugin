# ReadonlyREST CI

CI runs on GitHub Actions: `.github/workflows/ci.yml`. The Linux **test and build** jobs run on
**GitHub-hosted** `ubuntu-latest` runners inside the `beshultd/ror-ci-toolchains` image; Windows
jobs run on GitHub-hosted `windows-2025`. The **release** jobs are the exception and stay on the
shared self-hosted box: `upload_pre_ror`, `release_ror` and `publish_mvn` here, plus the standalone
`mirror-es-libs.yml` and `publish-pre-builds.yml`.

`ubuntu-latest` is 4 vCPU / 16 GB. It is slower per core than the paid runners these jobs can also
use, and the repo is public, so it costs nothing. `ci/toolchains/image.env` holds the toolchains
image tag, and every workflow that needs it sources that file.

The concurrency limit is **per account**, and `readonlyrest_kbn` shares it. `it_linux` and
`e2e_tests` set no `max-parallel`: a cap equal to the number of legs never binds, and it starts
throttling silently the day someone adds one more module. `it_windows` keeps a real cap, because it
has far more legs than the cap allows. Before you add a cap, or add legs, check the account limit
against every long job a develop push starts: `it_linux`, `it_windows`, `e2e_tests`, and the ROR
KBN pre-build that `e2e_order_kbn_images` dispatches.

**Some ES modules are not tested** — `UNTESTED_MODULES` in `TestMatrixPolicy` holds the current set.
They are still **built and published** on every release: `build_es7xx` and `release_es7xx`
enumerate modules with the `printEsModules` Gradle task, which reads `settings.gradle`, not the CI
matrix. Only integration-test coverage is gone, so a regression specific to one of those modules
ships untested. Removing a name from that set gives it its tests back, and costs runner minutes.

The release path (`upload_pre_ror`, `release_ror`, `publish_mvn`) and the standalone
`mirror-es-libs.yml` and `publish-pre-builds.yml` workflows run on the **self-hosted** box —
they push images and run for hours. `build-toolchains-image.yml` stays on `ubicloud-standard-4`.

**Every job calls `ci/free-host-disk.sh` right after checkout**, including a new one. The script
detects the runner and decides whether to reclaim disk; a job never decides that for it. On a
Windows job there is no equivalent step.

Every Linux job calls `ci/run-pipeline.sh` with a `ROR_TASK` — the scripts in this
directory contain the build logic; the workflow only orchestrates.

## Jobs

| Job | What it does | When |
|---|---|---|
| `ci_setup` | chooses where every `container:` job pulls the toolchains image from, and publishes the run flags the later `if:` conditions read | always |
| `toolchains_verify` | sanity-checks the toolchains image | always (stops the run early when the image is broken) |
| `discover` | derives every matrix: the ES majors and the modules each test family covers; see the [test matrix policy](#test-matrix-policy) | always |
| `required_checks` | audit build, cross-Scala compile, format, license | pushes + PRs |
| `unit_tests_linux` | `core:test` and friends | pushes + PRs |
| `optional_checks` | non-blocking checks (matrix; today: `cve_check` OWASP dependency-check, needs `NVD_API_KEY`) — failures annotate the run but never block it | pushes + PRs |
| `it_linux` | integration tests, one job per selected ES module | module selection follows the [test matrix policy](#test-matrix-policy) |
| `it_windows` | integration tests on native-Windows ES | module selection follows the [test matrix policy](#test-matrix-policy) |
| `unit_tests_windows` | `core:test` on Windows | manual `run_all_tests_on_windows` |
| `e2e_matrix` | resolves the e2e matrix and the build id that names every dev image | selected runs; see the [test matrix policy](#test-matrix-policy) |
| `e2e_order_kbn_images` | dispatches the ROR KBN dev image build **and waits for it** | selected runs |
| `e2e_build_es_images` | builds + publishes this repo's ROR ES dev image, one job per module | selected runs |
| `e2e_tests` | Cypress e2e suite, one job per selected ES module | selected runs; see the [test matrix policy](#test-matrix-policy) |
| `build_ror` | builds all plugin zips + bytecode-reuse guard, one job per ES major | PRs |


Manual actions (`workflow_dispatch` → `actionToPerform`): `run_all_tests_on_linux`,
`run_all_tests_on_windows`, `run_e2e_tests`. Releasing without testing is no longer one of them —
it has its own workflow, `manual-release.yml`.

## Where a release lives

`ci.yml` tests. It publishes nothing. `release.yml` publishes, and it has two ways in:

| Trigger | When | Guard |
|---|---|---|
| `workflow_run` | CI finished on develop or master | `workflow_run.conclusion == 'success'` — one value for the whole CI run |
| `workflow_dispatch` | an operator releases without waiting for tests | branch must be develop or master, and the operator types the `pluginVersion` that branch carries |

That single `conclusion` check is the whole reason for the split. Inside `ci.yml`, the manual path
was the only run whose test jobs are skipped on purpose, and carrying it forced `!cancelled()` and
a second branch into every release condition. `build_ror` is now the only job in `ci.yml` that needs
`!cancelled()` at all.

`release.yml` mirrors `ci.yml`'s job names, because it asks the same questions. `release_setup`
resolves the image, settles the branch, and on the manual path checks the version the operator
typed. `discover` then asks the build what to publish — the ES majors from `printEsMajors`, and
pre-release or release from `isPreReleaseVersion`.

Both workflows share two composite actions rather than two copies:

| Action | What it does | Used by |
|---|---|---|
| `resolve-toolchains-image` | picks the mirror or Docker Hub, and returns the image | `ci_setup`, `release_setup` |
| `verify-toolchains-image` | proves the baked Gradle home works | `toolchains_verify` (a whole job, to fail fast before the CI matrices), `discover` in `release.yml` (a step, being its first container job) |

### Two traps in `workflow_run`

**`github.ref` and `github.sha` point at the default branch**, not at the commit CI tested. Every
checkout in `release.yml` uses `env.RELEASE_SHA`, and the branch comes from `env.RELEASE_BRANCH`.
Miss one and you release code nobody tested. Never write `github.ref` in that file.

**GitHub only fires `workflow_run` for a copy of the file on the default branch**, which is `master`
here. A change to `release.yml` does nothing until it lands there, so it will look broken while you
develop it on a branch.

Also note `workflow_run` fires for CI runs on pull requests too, and their `head_branch` can be any
name. `release.yml` therefore checks `workflow_run.event == 'push'`, not just the branch filter.

### What you give up

Two runs, and the Actions graph draws no arrow between them. The release run's summary prints a link
back to the CI run that earned it. That is a breadcrumb, not a graph.

Other workflows in `.github/workflows/`, all manual or event-driven and independent of the
above: `build-toolchains-image.yml` (rebuilds the image every CI job runs in — weekly cron and
manual; see [The `container:` image](#the-container-image)), `mirror-es-libs.yml` (mirrors ES jars
into the libs store — see [S3 stores](#s3-stores)), `disk-probe.yml` (manually reports runner and
Docker disk usage), `pr-conventions.yml` (PR title/changelog checks), `actionstrings_gen.yml`
(regenerates the ES action-string lists in the docs repo), `publish-pre-builds.yml` (on-demand
ROR+ES dev images), `verify-publish-credentials.yml` (daily cron and manual — proves the Maven
Central credentials still work, because `publish_mvn` only runs on a release and skips silently
when the version is already published).

Two orchestration rules worth knowing before editing conditions:

- `concurrency` auto-cancels superseded **PR** runs only; branch pushes queue, so a push
  during a release run can never kill the release.
- GitHub skips a job whose `needs` contains a failed **or skipped** job. That rule is implicit, and
  an `if:` only switches it off when the expression holds a status check function — `success()`,
  `failure()`, `cancelled()` or `always()`. An `if:` of plain conditions keeps it.

  So `!cancelled() && needs.<X>.result == 'success'`, where `X` is already in `needs`, is the
  implicit rule spelled out, and buys nothing. Write it only when the job must survive some *other*
  dependency being skipped — and pair it with an explicit `== 'success'` on every dependency that
  must still be green, because `!cancelled()` lets failures through as well as skips.

  One job needs it: `build_ror`, which accepts `skipped` from the Windows and e2e families by name,
  because a draft PR runs neither. Everywhere else, let the implicit rule do the work.
- A job-level `if:` cannot read the `env` context, so a predicate that more than one job asks
  about has to be a job output. `ci_setup` publishes the three, and they are not the same
  question — do not collapse them:

  | Output | True for | Read by |
  |---|---|---|
  | `is_full_matrix` | develop, master, `epic/**` | the full test matrices in `discover` |
  | `is_release_branch` | develop, master | `publish`, the NVD cache write |
  | `is_master` | master | nothing in `ci.yml`; `release-jobs.yml` derives its own |
  | `is_automatic_run` | any run that is not a `workflow_dispatch` | `required_checks`, `optional_checks`, `publish` |
  | `runs_linux_tests` | automatic runs + manual `run_all_tests_on_linux` | `discover`, `unit_tests_linux` |
  | `runs_windows_tests` | automatic runs + manual `run_all_tests_on_windows` | `discover` |
  | `runs_e2e_tests` | automatic runs + manual `run_e2e_tests` | `discover` |
  | `is_manual_windows_run` | manual `run_all_tests_on_windows` only | `unit_tests_windows` |
  | `manual_action` | — the chosen action, `''` on an automatic run | `discover` |

  Every job `if:` reads one of these and compares it against `'true'`. `manual_action` is the one
  exception, and it is not read by any `if:`: `discover` picks a matrix by name, so it needs the
  value.

  Mind the pair that looks alike. `runs_windows_tests` covers automatic runs as well, and decides
  the Windows integration matrix. `is_manual_windows_run` covers the manual action alone, and starts
  the Windows unit tests, which no automatic run may start.

  A test family runs on every automatic run, and on a manual run only when the operator asked for
  that family. The three `runs_*` flags say so. `discover` reads them and empties the matrix of
  every family the run does not cover, so no test job carries a condition of its own — see
  [Derived matrices](#derived-matrices). `unit_tests_linux` is the exception, because it has no
  matrix to empty. `manual_action` serves the jobs that only a manual action may start.

  Always compare against the string `'true'`. An output is a string, so a bare
  `needs.ci_setup.outputs.is_release_branch` is truthy even when it holds `"false"`.

  Adding a flag: `${{ A && B }}` yields the string `"false"` when `A` is false, not `''`. That is
  why `manual_action` ends in `|| ''` — without it an automatic run would carry the word "false".

## What the build decides, not the workflow

A workflow must not work out a fact the build already knows. Three Gradle tasks answer for both
`ci.yml` and `release.yml`, so no YAML and no shell script parses `gradle.properties` or guesses at
a module list:

| Task | Answers | Written to |
|---|---|---|
| `printEsMajors` | which ES majors get built, uploaded and released | `build/es-modules/es-majors.txt` |
| `printTestMatrices` | which modules each test family covers, per [policy](#test-matrix-policy) | `build/ci-matrices/<name>.json` |
| `isPreReleaseVersion` | whether the configured `pluginVersion` is a pre-release (`-pre`) | stdout: `true` or `false` |

The first two write files, and callers must read those, not gradle stdout — configuration-time
logging can pollute it even under `--quiet`. `isPreReleaseVersion` prints one word instead, so its
caller takes the last line and **fails on anything that is not `true` or `false`**. A silently wrong
value there would publish a release as a pre-release, or the reverse.

`isPreReleaseVersion` is what decides `upload_pre_ror` against `release_ror`. That rule used to be a
`grep -- '-pre'` inside the release workflow, where nothing tested it and nothing else could reach
it. If the pre-release convention changes, change the task.

Reading a property from shell is a fourth case, and `ci-lib.sh`'s `gradle_property` owns it. Its own
comment states the rule: every reader goes through there, because a second parser drifts. The manual
release guard uses it to check the version the operator typed.

## Derived matrices

Every matrix of a run comes from the `discover` job, and so does every decision about which test
families the run covers. Nothing is written down twice: the modules that exist decide, so a new
module or a new ES major joins the matrices by itself.

A test job therefore carries no condition about the kind of run. An empty matrix skips its job, and
that is the single mechanism: a draft PR gets no Windows and no e2e leg, and a manual
`run_e2e_tests` gets neither a Linux nor a Windows one. Keep it that way — a second test in a job's
`if:` would state the same rule in a second place, and the two would drift.

`discover` publishes one ready matrix per job that fans out, so every consumer reads it the same way:

| Output | Consumed by | Shape |
|---|---|---|
| `build_matrix` | `build_ror` | `{"include":[{"ES_MAJOR":"9"},…]}` |
| `it_linux_matrix` | `it_linux` | `{"include":[{"ES_MODULE":"es94x"},…]}` |
| `it_windows_matrix` | `it_windows` | `{"include":[{"ES_MODULE":"es94x"},…]}` |
| `e2e_modules` | `e2e_matrix`, and the three e2e jobs after it | `["es94x","es818x","es717x"]` |

The `include` form matters. GitHub skips a matrix job whose include list is empty, so a family a run
does not cover needs no condition of its own: a draft PR emits an empty Windows and e2e matrix, and
both jobs skip before a runner boots. A bare `KEY: []` would instead stop the run with "Matrix vector
'KEY' does not contain any values".

`e2e_modules` is the one exception to the `include` form, because the e2e family does not fan out
from `discover`. It is a plain list, and `e2e_matrix` fans out from it instead: that job pairs each
module with an ELK version, which only a gradle call per module can resolve, and publishes the real
e2e matrix plus the build id. So the e2e jobs guard themselves on `e2e_modules != '[]'` rather than
on an empty matrix, and `build_ror` accepts a skipped `e2e_tests` when that list is empty.

It is the workflow, not the build, that picks which matrix a run takes: only the workflow knows the
branch and the event. `discover` needs the toolchains image for gradle, so it runs after
`toolchains_verify`.

Both rules live in `build-base` and are unit-tested: `EsModuleFinder.allSupportedEsMajors` (a module
counts for the major of its newest supported version, the rule `printEsModules` uses) and
`TestMatrixPolicy`.

This replaces seven hand-written lists. A missed edit there did not fail. The leg for the missing
module never ran, and CI stayed green. `run-pipeline.sh` and `publish-ror-plugins.sh` now also
refuse a major with no module, instead of looping zero times and returning 0.

## Test matrix policy

These rules define which ES modules each automatic test run covers. `TestMatrixPolicy` (build-base)
implements them and `discover` runs it, so the lists are derived and not written down. The terms
oldest, middle, and newest apply separately to each ES major version, and **middle** means the
module in the middle of that major's list, newest first.

| Development stage | Linux integration tests | Windows integration tests | E2E tests |
|---|---|---|---|
| Draft PR | Newest module for each ES major version | Not run | Not run |
| Ready PR | Fewer than 10 modules: oldest and newest. 10 or more modules: oldest, middle, and newest | Newest module for each ES major version | Newest module for each ES major version |
| `develop`, `master`, or `epic/**` | All modules | Oldest and newest modules for each ES major version | Newest module for each ES major version |

Windows integration tests and E2E tests do not run on ES 6. The modules in `UNTESTED_MODULES` run on
neither platform. A selection still picks oldest, middle and newest by how many modules the major
holds, and an untested pick moves to the nearest module that is tested — so the exclusion changes
which modules run, never how many. If a major
version has only one module, it is selected once. Manual actions can select the full supported Linux or Windows matrix. To release with no tests at
all, use the `manual-release.yml` workflow, not this one.

To see what a change does to the matrices, run `./gradlew printTestMatrices --quiet`. To change the
policy, change `TestMatrixPolicy` and this table together. Adding or removing a module changes which
`it_linux_*`, `it_win_*` and `e2e_*` jobs a run reports, so check branch protection when the policy
itself changes.

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
(image names, tag shape, workflow inputs, wait behaviour). Change the contract there, not here.

Four jobs, each with one responsibility:

```
e2e_matrix                     resolve versions once, publish the build id
   ├── e2e_order_kbn_images    dispatch the ROR KBN build, wait for it, verify the images  ─┐ in
   └── e2e_build_es_images     build + publish the ROR ES image, one job per module         ─┘ parallel
          └── e2e_tests        stack + Cypress only
```

- `e2e_matrix` finds the newest ES version of every module in the matrix
  (`:esXXXx:printNewestEsVersionForModule`). It publishes those versions, the build id and the ELK
  version list. It orders nothing and builds nothing. It runs in the toolchains container, because it
  calls Gradle to find the versions. Its `elk_versions` output lets the order job run outside that
  container.
- `e2e_order_kbn_images` sends **one** dispatch for all versions. Then it waits for that run, in the
  same shell. At the end it checks the registry for every image.
- `e2e_build_es_images` builds and pushes the ROR ES dev image from **this** commit, one job per
  module. It uses the same `publish_ror_es_prebuild_plugin` helper as the standalone pre-build task,
  so the sha-frozen-image skip still applies.
- `e2e_tests` runs once per version, in parallel. Both images exist when it starts. It clones the
  suite, starts the stack and runs Cypress.

### Why one job does both the dispatch and the wait

There are three reasons:

- **The failure points at the fault.** A ROR KBN build that fails, times out or publishes nothing
  fails `e2e_order_kbn_images`. The test jobs are then skipped. They do not report a failure that is
  not theirs. The job turns the shared lib's exit code into one line that names the cause, in the log
  and in the job summary.
- **"Re-run failed jobs" does the correct thing.** That button re-runs only the failed jobs, so it
  re-runs the order job. Its dispatch is a new `gh workflow run`, and its run lookup only accepts
  runs created around that dispatch. So it follows the new run, not the dead one. `e2e_matrix` is
  green and does not re-run, so the build id and the image names stay the same. The ES images from
  the first attempt stay valid.
- **The shared contract makes it necessary.** A dispatch is not told which run it created. The lib
  finds the run by title, in a window around the dispatch. Only the shell that dispatched the run can
  identify it, so only that shell can wait for it.

`e2e_order_kbn_images` and `e2e_build_es_images` are siblings under `e2e_matrix`. Neither needs the
other. So the critical path is `max(local ES build, ROR KBN run)`, plus one runner handoff.

The wait does not poll the registry. It waits on the dispatched ROR KBN **run**. What the wait
guarantees, and what this repo has to supply for it, is the contract at the top of the shared lib.
Read it there, not here. Three obligations fall on this repo:

- **Log in first.** The order job authenticates Docker before the wait. The registry check at the end
  of the wait does one pull per image. Docker Hub counts an anonymous pull against the runner's
  address (100 per 6h, shared), and an authenticated pull against the ROR account (200 per 6h).
- **Give the order job a token that can read the runs of the ROR KBN repo.** `ROR_GH_TOKEN` needs
  `actions:read`. A refused read stops the wait in seconds with code 8, and it names the necessary
  right. The job follows the run by these reads, so there is no other way.
- **Keep the title on our own pre-build run.** `publish-pre-builds.yml` carries
  `run-name: ROR ES pre-build ${{ inputs.tag || inputs.es_versions }}`. Every repo that dispatches it
  — the e2e repo and the ROR KBN repo — finds its run by that title, because a dispatch is told
  nothing about the run it creates. A dispatcher must send a tag, and the shared lib refuses a
  dispatch that sends none. So a run that a job waits for always shows `ROR ES pre-build <tag>`.
  The fallback applies only to a dispatch that sends no tag, and no search looks for such a title.
  Remove the line and every dispatch of this workflow fails, because no run can be recognised.

Both sides address the images by a per-run tag (`run-<build id>`). `e2e_matrix` creates the build id
and passes it down as a job output. No other job may derive it again. A partial re-run bumps the run
attempt but does not re-run `e2e_matrix`, and the two sides would then name different images.

Branch resolution — both other repos are asked for the branch of this PR first. The e2e clone then
tries the base branch, `develop`, `master`; the base branch matters, because a change based on
`develop` must use the `develop` suite, not `master`. The ROR KBN branch name is passed through as
it is: if the KBN repo has no such branch, its pre-build workflow falls back to `develop` on its
own side.

Module selection follows the [test matrix policy](#test-matrix-policy). Job names are built from
the module, not the ES version, so branch-protection checks survive a version bump. The same
`<leg>_<module>` shape is used by all three test families: `it_linux_es94x`, `it_win_es94x`,
`e2e_es94x`.

On failure the job uploads the Cypress videos and screenshots to the E2E_REPORTS store
(`ci/upload-cypress-artifacts-to-s3.sh`), under `<prefix>/<date>/build_<run id>/<module>_<elk>/`.
S3, not GitHub artifacts: those count against the metered Actions-storage quota.

Needs `ROR_ENT_ACTIVATION_TOKEN` (the suite refuses to start without it) and `ROR_GH_TOKEN` (to
dispatch the ROR KBN build and read its status).

## S3 stores

ROR uploads to S3-compatible stores through one path: `ci/s3-uploader.sh` (curl + SigV4) under
`ci-lib.sh`'s `upload_using_aws_s3_uploader` / `..._to_key`, driven by `ci/upload-files-to-s3.sh`.
One credential set — `ROR_S3_{ENDPOINT_URL,BUCKET,REGION,ACCESS_KEY_ID,SECRET_ACCESS_KEY}` —
serves every store. A store is named, and its name selects only the key prefix it writes under:
`ROR_S3_PATH_{ARTIFACTS,LIBS,E2E_REPORTS}`. Resolve that in one place (`ci-lib.sh`) and nowhere
else.

An earlier revision claimed each store had its own credentials "because the gateway authorizes
each prefix separately". That was never true: the artifacts key writes `builds/`, `libs/` and
`e2e_reports/` alike.

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

**16 repository secrets + 8 variables**, all managed in the Doppler project `ror_ci` (config
`prd`) and synced to this repo. Change them in Doppler, never in GitHub — the sync overwrites.

| Secret | Purpose |
|---|---|
| `ROR_S3_ACCESS_KEY_ID` / `ROR_S3_SECRET_ACCESS_KEY` | the one S3 key pair; writes `builds/`, `libs/` and `e2e_reports/` |
| `DOCKER_HUB_USER` / `DOCKER_HUB_RW_TOKEN` | the push account. It pushes the ROR and toolchains images, and authenticates the pulls of the same job. A job maps it into `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_PASSWORD`, which is the one pair `configure-docker.sh` reads |
| `DOCKER_HUB_USER` / `DOCKER_HUB_RO_TOKEN` | the read-only token; it cannot push — it is refused push scope. `unit_tests_linux` and `it_linux` map it into `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_PASSWORD`, which authenticates the pulls their steps make. No `container:` pull uses it, because the runner makes that pull before step 1. Without it, a pull request from a fork continues with anonymous pulls, and every other event stops |
| `ROR_ENT_ACTIVATION_TOKEN` | ROR PRO/Enterprise key the e2e stack boots with. **The secret is renamed; the env var handed to the container stays `ROR_ACTIVATION_KEY`, which is the customer-facing name** |
| `ROR_GH_TOKEN` | cross-repo GitHub PAT: dispatches the ROR KBN image build, reads run status, pushes docs |
| `NVD_API_KEY`, `OSS_INDEX_USERNAME`, `OSS_INDEX_PASSWORD` | `cve_check` feeds |
| `MAVEN_REPO_USER`, `MAVEN_REPO_PASSWORD`, `MAVEN_STAGING_PROFILE_ID`, `GPG_KEY_ID`, `GPG_PASSPHRASE` | Maven Central publishing |
| `PGP_SECRET_KEY_B64` | base64 of `secret.pgp`; the publish step decodes it to `.travis/secret.pgp`. Create with `base64 -w0 secret.pgp \| gh secret set PGP_SECRET_KEY_B64` |

Variables (`vars.NAME`, non-sensitive): `ROR_S3_{ENDPOINT_URL,REGION,BUCKET}` and
`ROR_S3_PATH_{ARTIFACTS,LIBS,E2E_REPORTS}`. Only the key pair is secret. Keeping these as
variables is deliberate: a non-secret stored as a secret makes GitHub redact every substring
match of it, and a bucket named `beshu` turns `beshultd/...` into `***ltd` in every log line.
The `ROR_S3_PATH_LIBS` value is optional —
unset (or empty, which is what an undefined `vars.X` expands to) falls back to the defaults in
`LibsStore`, which is also what a local build resolves against.

Release tags push via the checkout token (`release_ror` has `permissions: contents: write`)
— no SSH deploy key. Fork PRs get no secrets (GitHub default); `cve_check` and docker auth
degrade instead of failing.

### Docker authentication

Every job that pulls or pushes a Docker Hub image uses one command, and no other:

```bash
source ci/configure-docker.sh
```

The script reads one pair of variables, `DOCKER_REGISTRY_USER` / `DOCKER_REGISTRY_PASSWORD`, and
the job supplies it: a job that pushes maps `DOCKER_HUB_RW_TOKEN` into it, a job that only pulls
maps `DOCKER_HUB_RO_TOKEN`. The script then authenticates both Docker clients with that pair: the
docker CLI, with a login, and testcontainers, with `DOCKER_AUTH_CONFIG`. Both halves are necessary. The docker CLI
does not read `DOCKER_AUTH_CONFIG`. Docker CLI 27, which the toolchains image contains, ignores
that variable.

Credentials that do not work always stop the job. The script never falls back to anonymous pulls
in that case, because a bad login must not hide itself. An expired token thus fails in the job
that owns it, and not as a rate-limit failure somewhere else one hour later.

`DOCKER_AUTH_REQUIRED` covers one case only: the job supplied no credentials at all. The script
reads the event and decides. A pull request from a fork continues, and its pulls are anonymous:
GitHub gives it no secrets, and its tests must still run. Every other run stops, because a missing
secret there is a mistake. A job that sets the variable overrides the decision, and only the literal
`false` lets the job continue.

No workflow states this. Two jobs held the same expression before, and a new job had to copy it.

Do not put a `docker login` in a workflow. Two mechanisms with different credentials hide each
other, because a CLI that reads `DOCKER_AUTH_CONFIG` gives that variable priority over the login.

The script cannot authenticate the `container:` image, because the runner pulls that image before
step 1 starts. Nothing else authenticates it. That pull is anonymous, and the registry that
`ci_setup` chose answers it: `mirror.gcr.io` on the normal path, Docker Hub on the fallback. See
[The `container:` image](#the-container-image).

### Docker Hub pull mirror

`ci/configure-docker.sh` also points Docker at a pull-through cache, `mirror.gcr.io`. Authentication
alone does not stop every rejection, because two limits apply:

- The **pull quota**, 200 pulls per 6 hours on the free plan. It follows the account once a job
  authenticates, so the login controls it. One full `ci.yml` run makes about 60 pulls.
- The **abuse rate limit**. Docker applies it per IP address and ignores the account. A runner draws
  an ephemeral address from the Ubicloud pool (`https://api.ubicloud.com/ips-v4`, 23 Hetzner blocks)
  and shares it with other tenants, so a neighbour can fill the bucket. The job then fails on a bare
  `429 Too Many Requests`, and no credential prevents it.

The mirror serves `docker.io`. It cannot serve `docker.elastic.co` or `ghcr.io`, and it cannot accept
a push. Three clients pull images inside a job, and the script sets all three from one variable. A
fourth pull happens before any step and needs an answer of its own — see
[The `container:` image](#the-container-image):

| Client | Setting | Reaches |
|---|---|---|
| buildx and BuildKit | `ROR_DOCKER_HUB_MIRROR`, which gradle turns into `--config build-base/buildkitd.toml` | the `FROM` lines in `es*x/Dockerfile` |
| the test suite | `ROR_DOCKER_HUB_MIRROR_PREFIX`, which `DockerHubMirror` reads | the images each call site names |
| the toolchains build | the same variable, passed as `--build-arg MIRROR` | the five `FROM` lines in `ci/toolchains/JdkToolchains.Dockerfile` |

No client reads another client's setting, which is why there are three. All three work in a
`container:` job and on a bare runner, and none of them needs a privilege.

The BuildKit setting keeps the original `docker.io` image identity, so BuildKit falls back to Docker
Hub when the mirror cannot serve an image. The other two rewrite the image name itself, for example
`coredns/coredns:1.13.2` becomes `mirror.gcr.io/coredns/coredns:1.13.2`, and `eclipse-temurin:17-jdk`
becomes `mirror.gcr.io/library/eclipse-temurin:17-jdk`. A rewritten name has no Docker Hub fallback
and the pull fails if the mirror does not serve the tag. Keep `DockerHubMirror` call sites limited to
images known to be available from the mirror. For the toolchains build, `ROR_DOCKER_HUB_MIRROR` set
to `'false'` on the job restores the Docker Hub path.

The test suite names every image it mirrors, one call site at a time. Do not set
`TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` instead: testcontainers applies that prefix to every name
without a registry host, and a locally built name looks the same as a Docker Hub name. It rewrote our
own `ror-it-es:<hash>` image and then tried to pull it, which failed every `it_linux` leg with
"manifest unknown". Ryuk still comes from Docker Hub for the same reason.

`ROR_DOCKER_HUB_MIRROR=false` switches the mirror off for one job. No job sets it. It is there for
the day the mirror cannot serve an image a job needs.

The flag answers one question: may this job read a cached answer? It does not follow from what the
job pushes. The two workflows that push most, `publish-pre-builds` and `build-toolchains-image`, need the
mirror most, because a 429 hits their base-image pulls. A mirror rewrites pulls only. A push names
`beshultd/...` and goes to Docker Hub whatever the mirror says.

`e2e_tests` keeps the mirror as well, although it pulls the ROR ES and ROR KBN images that two other
runs pushed a moment before, where a cached answer can be stale. The e2e repo settles that image by
image: it names the images it takes from the cache, and the ROR images are not among them, so those
two pulls go to Docker Hub. The job gains the rest: the digest-pinned gosu and corretto pulls of the
ES image build, and every image the e2e stack takes from the cache.

Two more things stay off the mirror by themselves, and both must remain so:

- `docker manifest inspect` (`docker_image_exists`) and `docker buildx imagetools create`. They read
  a tag we pushed seconds ago. Both run in the CLI, which reads neither `buildkitd.toml` nor any of
  the variables above, so both address Docker Hub by themselves.
- Every push. A pull-through cache is read-only, so `beshultd/*` images go to Docker Hub.

### The `container:` image

The runner pulls the job `container:` image before step 1 starts. `ci/configure-docker.sh` cannot
reach that pull, so it sets neither the mirror nor the login for it. CI jobs and their matrix legs
share the image, which makes it the most pulled image of a run. Docker Hub answered a whole run with
`429 toomanyrequests` on 2026-08-26.

`container: image:` can read a job output. The `ci_setup` job runs
`ci/resolve-toolchains-image.sh` once, and the `&toolchains_container` anchor reads its output: the
name to pull. That job holds no container itself, and it cannot: it is the job that picks one.

A mirrored name carries the digest, not the tag: `mirror.gcr.io/beshultd/ror-ci-toolchains@sha256:…`.
The jobs of one run start hours apart, and a tag can move between the check and a pull. A Docker Hub
name keeps the tag, because no digest is proven in that case.

So `ci_setup` asks the mirror about the digest, not about the tag. A digest names the bytes,
and a cache cannot answer it with the wrong image. The rebuild records the digest of its push in the
Actions cache. `ci_setup` then sends the mirror one HEAD request for that digest. The request downloads
no image, and it reaches no Docker Hub:

| What the script finds | What the run pulls |
|---|---|
| no digest on record | Docker Hub |
| the mirror cannot serve the digest | Docker Hub |
| the mirror serves the digest | the mirror |

Docker Hub is the safe answer, so a miss costs speed only. `ci_setup` writes the choice to the step
summary, so the run page shows which registry a run used, and why.

The mirror fetches a digest it has never held. So a run keeps the mirror in the hours after a
rebuild, before the mirror knows the new tag. A question about the tag would lose the mirror in that
window, where the recorded digest is newest.

The cache key holds the tag and the rebuild's run id. A key is write-once, so each rebuild adds an
entry and `ci_setup` restores the newest by prefix. GitHub drops an entry that nothing reads for 7 days,
and `ci_setup` reads this one every run. A new tag matches no entry, so its runs use Docker Hub until
the next rebuild. The same holds now: run **Build toolchains image** once, on `develop`, to write the
first digest.

The key and the prefix put two hyphens after the tag. One hyphen keeps `9.2.1` apart from `9.2.10`,
but not from `9.2.1-arm64`: that key starts with the prefix of `9.2.1`. The runs of the shorter tag
could then restore the digest of the longer one, and the mirror serves any digest of the repository,
so those jobs would run the wrong image. Two hyphens keep the two apart, because no tag here ends
with a hyphen.

An entry belongs to the ref that saved it. Every run also reads the default branch, `develop`. A
pull request run reads its merge ref and the base branch, but never the head branch. A rebuild
dispatched from a feature branch therefore writes an entry that no pull request run finds.

A dispatch off `develop` stays allowed. The image bakes the commit's Gradle dependencies, so a
branch that bumps one needs its own tag and its own build, and the push is what that branch needs.
Only the cache entry goes to waste, so `record_digest` raises a `::warning::` instead of a refusal.
Dispatch the workflow again on `develop` after the merge.

The rebuild does not save that entry. It runs on an Ubicloud runner, and an Ubicloud runner keeps
its own Actions cache. A GitHub-hosted runner cannot read it, and `ci_setup` is GitHub-hosted. So the
digest travels as a job output to `record_digest`, a small job on `ubuntu-latest`, which saves it.
Both ends then read one store.

The rebuild lives in `build-toolchains-image.yml`, not in `ci.yml`. It takes up to four hours, and
no test run waits for it. A rebuild in `ci.yml` would also hold the `develop` concurrency group for
those hours, and every push to `develop` would queue behind it.

The rebuild keeps a concurrency group of its own, `build-toolchains-image`. Two rebuilds push one
tag and file two cache entries, and `ci_setup` takes the newest entry, which need not hold the manifest
that Docker Hub keeps. A second run waits instead, because cancelling a four-hour build wastes it.

The pull sends no credentials, on either path. `mirror.gcr.io` refuses a Docker Hub login, so the
`credentials:` block must be absent when the name is mirrored. An empty value does not remove it.
GitHub rejects `username: ''` with `Unexpected value ''` and stops the run before step 1. YAML cannot
drop a key on a condition, and `credentials:` is a mapping, so no expression reaches it. The block is
therefore absent on both paths.

Docker Hub counts an anonymous pull against the runner's address, and an authenticated pull against
the account. So the fallback pull shares an address limit with every other anonymous puller. That
path is rare. It opens when a tag has no recorded digest, and the next rebuild closes it.

Every run now pulls this image the way a fork always did, and a fork gains the most. It has no
secrets, so Docker Hub always answered it anonymously. It now reads `mirror.gcr.io`, which sets no
limit. A fork run reads the base branch, so it finds the digest that a `develop` rebuild saved.

One limit. The choice is made once, for the whole run. If the mirror stops answering during a run,
the jobs that already took the mirrored name fail. The runner's pull has no fallback of its own.

`ghcr.io` is the other direction. It would remove the digest bookkeeping, because the pull would no
longer cross a cache, and a public package needs no `credentials:` block at all. It costs a tag in
`image.env`, a `docker login ghcr.io` with `GITHUB_TOKEN`, `packages: write`, and a package the org
makes public. It would not retire the mirror, which `buildx`, the test suite and the toolchains
build still need for their own Docker Hub pulls. This change does not settle that question.
