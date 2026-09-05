# ReadonlyREST CI

CI runs on GitHub Actions: `.github/workflows/ci.yml`. The Linux **test and build** jobs run on
**GitHub-hosted** `ubuntu-latest` runners inside the `beshultd/ror-ci-toolchains` image; Windows
jobs run on GitHub-hosted `windows-2025`. The **release** jobs are the exception and stay on the
shared self-hosted box: `upload_pre_ror`, `release_ror` and `publish_mvn` here, plus the standalone
`mirror-es-libs.yml` and `publish-pre-builds.yml`.

`ubuntu-latest` is 4 vCPU / 16 GB, the same shape as the `ubicloud-standard-4` these jobs used to
run on, but **not the same throughput**: measured on the 10-module PR set, an `it_linux` leg takes
25-35 min here against 21-27 min on Ubicloud, and the `build_ror` legs are 40-70% slower. That is
the deliberate trade — this was the single biggest line of the Ubicloud bill and it now costs
nothing. `ci/toolchains/image.env` holds that image's tag, and every workflow that needs
it sources that file. The repo is public, so GitHub-hosted runners are free. The concurrency limit
is 40 jobs **per account** (GitHub Pro), shared with `readonlyrest_kbn`. A develop push has 29
`it_linux` legs, 6 `it_windows` legs and 3 `e2e` legs — 38 long jobs — so `it_linux` sets
`max-parallel: 29` and the whole matrix starts in one wave, leaving two slots for the ROR KBN
pre-build that `e2e_prepare` dispatches.

**Five ES series have no test leg**: 7.3, 7.4, 7.9, 7.11 and 7.14. The customer portal's downloads
table shows zero downloads of their plugin builds in the last two years, and dropping them is what
makes the matrix fit. They are still **built and published** on every release: `build_es7xx` and
`release_es7xx` enumerate modules with the `printEsModules` Gradle task, which reads
`settings.gradle`, not the CI matrix. Only integration-test coverage is gone, so a regression
specific to one of those series would ship unnoticed.

The release path (`upload_pre_ror`, `release_ror`, `publish_mvn`) and the standalone
`mirror-es-libs.yml` and `publish-pre-builds.yml` workflows run on the **self-hosted** box —
they push images and run for hours. `build-toolchains-image.yml` stays on `ubicloud-standard-4`.

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
| `it_linux` | integration tests, one job per ES version | 10-version subset on PRs, full 29 on develop/master/epic and manual |
| `it_windows` | integration tests on native-Windows ES | 3 on PRs, 6 on branches, full 28 on manual |
| `unit_tests_windows` | `core:test` on Windows | manual `run_all_tests_on_windows` |
| `e2e_prepare` | resolves the e2e matrix + starts the ROR KBN image build | pushes + PRs (not drafts) |
| `e2e_tests` | Cypress e2e suite, one job per ES version | pushes + PRs (not drafts) |
| `build_ror` | builds all plugin zips + bytecode-reuse guard | PRs |
| `determine_ci_type` → `upload_pre_ror` / `release_ror` / `publish_mvn` | release pipeline | develop/master pushes + manual `release_without_testing` |

Manual actions (`workflow_dispatch` → `actionToPerform`): `run_all_tests_on_linux`,
`run_all_tests_on_windows`, `run_e2e_tests`, `release_without_testing`.

Other workflows in `.github/workflows/`, all manual or event-driven and independent of the
above: `build-toolchains-image.yml` (rebuilds the image every CI job runs in — weekly cron and
manual; see [The `container:` image](#the-container-image)), `mirror-es-libs.yml` (mirrors ES jars
into the libs store — see [S3 stores](#s3-stores)), `disk-probe.yml` (manually reports runner and
Docker disk usage), `pr-conventions.yml` (PR title/changelog checks), `actionstrings_gen.yml`
(regenerates the ES action-string lists in the docs repo), `publish-pre-builds.yml` (on-demand
ROR+ES dev images).

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
(image names, tag shape, workflow inputs, wait behaviour). Change the contract there, not here.

Two jobs, because the two plugin images are built in different places:

- `e2e_prepare` runs once. It resolves the newest ES version of every module in the matrix
  (`:esXXXx:printNewestEsVersionForModule`), publishes that matrix, and dispatches **one** ROR
  KBN image build for all those versions. Non-blocking: it does not wait for the build.
- `e2e_tests` runs once per version, in parallel. It builds and pushes the ROR ES dev image from
  **this** commit, waits for the ROR KBN image, then runs the suite against both.

The wait is not a poll of the registry: a leg waits on the dispatched ROR KBN **run**. What the wait
guarantees, and what this repo has to supply for it, is the contract at the top of the shared lib.
Read it there, not here. Four obligations fall on this repo:

- **Pass the run down.** `e2e_prepare` fails if it cannot identify the run it started, and publishes
  it as the `kbn_run_id` and `kbn_run_url` outputs. `ci.yml` hands both to every `e2e_tests` leg,
  together with `ROR_GH_TOKEN`. A leg with an empty run id reports broken wiring and stops.
- **Log in first.** Each leg authenticates Docker before the wait, so the registry check the wait
  makes at the end counts against the ROR account and not against the runner address.
- **Give the leg a token that can read the runs of the ROR KBN repo.** `ROR_GH_TOKEN` needs
  `actions:read`. A refused read ends the wait with code 8 in seconds, and names the right it
  needs. Reading it is how the leg follows the run, so there is no way round it.
- **Keep the title on our own pre-build run.** `publish-pre-builds.yml` carries
  `run-name: ROR ES pre-build ${{ inputs.tag || inputs.es_versions }}`. Every repo that dispatches it
  — the e2e repo and the ROR KBN repo — finds its run by that title, because a dispatch is told
  nothing about the run it creates. A dispatcher must send a tag, and the shared lib refuses a
  dispatch that sends none. So a run that a job waits for always shows `ROR ES pre-build <tag>`.
  The fallback applies only to a dispatch that sends no tag, and no search looks for such a title.
  Remove the line and every dispatch of this workflow fails, because no run can be recognised.

One consequence shapes this side: the wait ends when the ROR KBN run ends, not when one image
appears, so all three legs reach their suite at about the same time. The Gradle build of the ROR ES
image runs before the wait and absorbs most of that time.

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
step 1 starts. Nothing else authenticates it. That pull is anonymous, and the registry that `setup`
chose answers it: `mirror.gcr.io` on the normal path, Docker Hub on the fallback. See
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
reach that pull, so it sets neither the mirror nor the login for it. Ten jobs and their matrices
share the image, which makes it the most pulled image of a run. Docker Hub answered a whole run with
`429 toomanyrequests` on 2026-08-26.

`container: image:` can read a job output. The `setup` job runs `ci/resolve-toolchains-image.sh`
once, and the `&toolchains_container` anchor reads its output: the name to pull.

A mirrored name carries the digest, not the tag: `mirror.gcr.io/beshultd/ror-ci-toolchains@sha256:…`.
The jobs of one run start hours apart, and a tag can move between the check and a pull. A Docker Hub
name keeps the tag, because no digest is proven in that case.

So `setup` asks the mirror about the digest, not about the tag. A digest names the bytes, and a
cache cannot answer it with the wrong image. The rebuild records the digest of its push in the
Actions cache. `setup` then sends the mirror one HEAD request for that digest. The request downloads
no image, and it reaches no Docker Hub:

| What the script finds | What the run pulls |
|---|---|
| no digest on record | Docker Hub |
| the mirror cannot serve the digest | Docker Hub |
| the mirror serves the digest | the mirror |

Docker Hub is the safe answer, so a miss costs speed only. `setup` writes the choice to the step
summary, so the run page shows which registry a run used, and why.

The mirror fetches a digest it has never held. So a run keeps the mirror in the hours after a
rebuild, before the mirror knows the new tag. A question about the tag would lose the mirror in that
window, where the recorded digest is newest.

The cache key holds the tag and the rebuild's run id. A key is write-once, so each rebuild adds an
entry and `setup` restores the newest by prefix. GitHub drops an entry that nothing reads for 7 days,
and `setup` reads this one every run. A new tag matches no entry, so its runs use Docker Hub until
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
its own Actions cache. A GitHub-hosted runner cannot read it, and `setup` is GitHub-hosted. So the
digest travels as a job output to `record_digest`, a small job on `ubuntu-latest`, which saves it.
Both ends then read one store.

The rebuild lives in `build-toolchains-image.yml`, not in `ci.yml`. It takes up to four hours, and
no test run waits for it. A rebuild in `ci.yml` would also hold the `develop` concurrency group for
those hours, and every push to `develop` would queue behind it.

The rebuild keeps a concurrency group of its own, `build-toolchains-image`. Two rebuilds push one
tag and file two cache entries, and `setup` takes the newest entry, which need not hold the manifest
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
