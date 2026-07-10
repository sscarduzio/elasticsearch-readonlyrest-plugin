# Azure Pipelines → GitHub Actions port

`.github/workflows/ci.yml` ports `azure-pipelines.yml` to GitHub Actions.
Linux jobs run on **Ubicloud** (`ubicloud-standard-4` = 4 vCPU / **16 GB**) inside the
same `beshultd/ror-ci-toolchains` image; Windows IT runs on **Blacksmith**
(`blacksmith-4vcpu-windows-2025`).

The **build logic is unchanged** — every Linux job still calls `ci/run-pipeline.sh`
with a `ROR_TASK`, exactly as Azure did. `run-pipeline.sh` and its sourced libs
contain no Azure-specific constructs (verified), so only the orchestration layer
was rewritten.

## Stage → job mapping

| Azure stage | GH job | Notes |
|---|---|---|
| SUPERSEDE_GUARD | — | replaced by `concurrency: cancel-in-progress` (native) |
| DISK_PROBE | — | dropped (Azure host-recon only; N/A on Ubicloud) |
| ES_S3_UP | `es_s3_up` | `newes/*` branches only |
| BUILD_TOOLCHAINS_IMAGE | `build_toolchains_image` | schedule + manual |
| TOOLCHAINS_VERIFY | `toolchains_verify` | |
| OPTIONAL_CHECKS (CVE) | `cve_check` | `continue-on-error` |
| REQUIRED_CHECKS | `required_checks` | 4-way matrix |
| TEST (unit) | `unit_tests` | |
| TEST (Linux IT) | `it_linux` | full 34-version matrix; PR subset gated per-leg |
| TEST (Windows IT) | `it_windows` | Blacksmith; full 33-version matrix; PR/branch subset gated per-leg |
| BUILD_ROR | `build_ror` | PR only |
| DETERMINE_CI_TYPE | `determine_ci_type` | sets `is_release` output |
| UPLOAD_PRE_ROR | `upload_pre_ror` | pre-release |
| RELEASE_ROR (+ without-testing) | `release_ror` | manual `release_without_testing` folds in via `if` |
| PUBLISH_MVN_ARTIFACTS (+ without-testing) | `publish_mvn` | master release only |

`RELEASE_ROR_WITHOUT_TESTING` / `PUBLISH_MVN_ARTIFACTS_WITHOUT_TESTING` are not
separate jobs: the manual `release_without_testing` path is expressed in the `if`
of `release_ror` / `publish_mvn` (GitHub can gate a single job on both auto and
manual conditions, so the Azure duplication isn't needed).

## Azure feature translations

| Azure | GitHub |
|---|---|
| `$(System.AccessToken)` | `secrets.GITHUB_TOKEN` |
| `##vso[task.setvariable]` | `>> $GITHUB_OUTPUT` / `>> $GITHUB_ENV` |
| `Cache@2` | `actions/cache@v4` |
| `DownloadSecureFile@1` (secret.pgp) | base64 secret `PGP_SECRET_KEY_B64`, decoded in-step |
| `PublishTestResults@2` | `actions/upload-artifact` of `**/TEST*.xml` |
| pipeline var subset gating (PR vs develop) | per-leg `gate` step (one matrix, not three) |
| `free-host-disk.sh` (`target: host`) | runs normally; `AGENT_ISSELFHOSTED=1` makes it a no-op (Ubicloud has no Azure bloat to reclaim) |

## Before first run — do these

1. **Register runners.** Install the Ubicloud and Blacksmith GitHub Apps on the
   repo and confirm the labels resolve. I could not verify registration from here
   (the API needs a GitHub-App token). If Blacksmith Windows isn't available on your
   plan, the `it_windows` job will queue forever — check first.
2. **Set secrets/variables.** Run `ci/github/set-secrets.sh` (fill values first) or
   use the UI. See `SECRETS.md` for the full inventory (17 secrets + 8 variables).
3. **Confirm the deploy-key question** in SECRETS.md: `GH_DEPLOY_KEY_B64` may be
   unnecessary if release tagging uses the checkout token (`persist-credentials`).
4. **Testcontainers on Ubicloud.** Linux jobs run inside `container:`; testcontainers
   started by Gradle use the host Docker daemon (socket auto-mounted by Actions).
   Ubicloud provides Docker on the host, so this mirrors the Azure model — but the
   first IT run is the thing to watch (a `core_tests`/one-IT smoke run is enough).

## Suggested rollout

Don't flip `azure-pipelines.yml` off yet. Run `ci.yml` in parallel on a branch,
confirm `required_checks` + `unit_tests` + one IT leg go green on Ubicloud and one
Windows leg on Blacksmith, then widen. `workflow_dispatch` lets you trigger a
Linux-only or Windows-only full run manually while validating.

## Not ported / deliberately dropped

- **DISK_PROBE** — Azure host-disk recon; irrelevant on Ubicloud VMs.
- **SUPERSEDE_GUARD** and `ci/stale-azure-pipeline-runs-canceler.sh` — replaced by
  `concurrency`. The script stays in the tree (still used by Azure until cutover).
- **Sharded-log artifact publishing** (`IT_PARALLELISM > 1` per-shard logs) — the
  env var is passed through, but the Azure-specific `PublishBuildArtifacts` of
  `sharded-logs/` isn't re-added; `**/TEST*.xml` upload covers results. Add back if
  you raise `IT_PARALLELISM` on Ubicloud.
