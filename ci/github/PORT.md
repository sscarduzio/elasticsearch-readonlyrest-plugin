# Azure Pipelines → GitHub Actions port

`.github/workflows/ci.yml` ports `azure-pipelines.yml` to GitHub Actions.
Linux jobs run on **Ubicloud** (`ubicloud-standard-4` = 4 vCPU / **16 GB**) inside the
same `beshultd/ror-ci-toolchains` image; Windows jobs run on **GitHub-hosted
`windows-2025`** (free for public repos — the same VM family Azure used).

The **build logic is unchanged** — every Linux job still calls `ci/run-pipeline.sh`
with a `ROR_TASK`, exactly as Azure did. `run-pipeline.sh` and its sourced libs
contain no Azure-specific constructs (verified), so only the orchestration layer
was rewritten. All 54 `ROR_TASK` values referenced by the workflow exist in the script.

**Azure triggers are disabled** (`trigger: none`, `pr: none`, schedules removed) in the
same PR; the Azure pipeline stays manually runnable as a fallback until it's deleted.

## Stage → job mapping

| Azure stage | GH job | Notes |
|---|---|---|
| SUPERSEDE_GUARD | — | replaced by `concurrency` (cancel-in-progress **for PRs only**; branch pushes queue, so an in-flight release is never cancelled — Azure `batch: true` semantics) |
| DISK_PROBE | — | dropped (Azure host-recon only; N/A on Ubicloud) |
| ES_S3_UP | `es_s3_up` | `newes/*` branches only; ordered **before** all check/test jobs, as on Azure |
| BUILD_TOOLCHAINS_IMAGE | `build_toolchains_image` | schedule + manual |
| TOOLCHAINS_VERIFY | `toolchains_verify` | |
| OPTIONAL_CHECKS (CVE) | `cve_check` | `continue-on-error`; monthly cache key (`yyyyMM`), Azure `Cache@2` semantics |
| REQUIRED_CHECKS | `required_checks` | 4-way matrix |
| TEST (unit) | `unit_tests` | |
| TEST (Linux IT ×3 jobs) | `it_linux` | **dynamic matrix** from `setup`: full 34-version set on develop/master/epic + manual, 10-version subset on PRs. Skipped legs never boot a VM. |
| TEST (Windows IT ×3 jobs) | `it_windows` | dynamic matrix: 7 versions on develop/master/epic, 3 on PRs, full 33 on manual `run_all_tests_on_windows` |
| TEST (Windows unit, manual) | `unit_tests_windows` | manual `run_all_tests_on_windows` only |
| BUILD_ROR | `build_ror` | PR only; gates on Linux **and Windows** results (Azure `succeeded('TEST')` parity) |
| DETERMINE_CI_TYPE | `determine_ci_type` | `!cancelled()` + explicit result checks — see below |
| UPLOAD_PRE_ROR | `upload_pre_ror` | pre-release, auto only |
| RELEASE_ROR (+ without-testing) | `release_ror` | `permissions: contents: write` for the tag push; `TRAVIS_BUILD_NUMBER=github.run_number` for the tag message |
| PUBLISH_MVN_ARTIFACTS (+ without-testing) | `publish_mvn` | master release only |

### The skipped-needs trap (why release jobs check `needs.<job>.result` explicitly)

GitHub skips a job whose `needs` contains a skipped job (implicit `success()`).
The manual `release_without_testing` path *intentionally* skips the test jobs, so
`determine_ci_type` / `release_ror` / `publish_mvn` use `!cancelled()` plus explicit
two-arm conditions, mirroring Azure:

- **auto arm**: push to develop/master, `required_checks`/`unit_tests`/`it_linux`/`it_windows`
  all `result == 'success'`
- **manual arm**: `workflow_dispatch` + `actionToPerform == 'release_without_testing'` +
  `toolchains_verify.result == 'success'`

## Trigger-scenario walk-through (regression table)

Which jobs run per trigger (✓ run / − skip), matching the Azure stage conditions:

| Job | PR | push develop | push master | push newes/* PR | dispatch: linux tests | dispatch: win tests | dispatch: release w/o testing | schedule |
|---|---|---|---|---|---|---|---|---|
| setup | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| build_toolchains_image | − | − | − | − | − | − | − | ✓ |
| toolchains_verify | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | − |
| es_s3_up | − | − | − | ✓ | − | − | − | − |
| cve_check | ✓ | ✓ | ✓ | ✓ | − | − | − | − |
| required_checks | ✓ | ✓ | ✓ | ✓ | − | − | − | − |
| unit_tests | ✓ | ✓ | ✓ | ✓ | ✓ | − | − | − |
| it_linux | ✓ (10) | ✓ (34) | ✓ (34) | ✓ (10) | ✓ (34) | − | − | − |
| it_windows | ✓ (3) | ✓ (7) | ✓ (7) | ✓ (3) | − | ✓ (33) | − | − |
| unit_tests_windows | − | − | − | − | − | ✓ | − | − |
| build_ror | ✓ | − | − | ✓ | − | − | − | − |
| determine_ci_type | − | ✓ | ✓ | − | − | − | ✓ (on develop/master ref) | − |
| upload_pre_ror | − | ✓ if `-pre` | ✓ if `-pre` | − | − | − | − | − |
| release_ror | − | ✓ if release | ✓ if release | − | − | − | ✓ if release | − |
| publish_mvn | − | − | ✓ if release | − | − | − | ✓ if release+master | − |

## Azure feature translations

| Azure | GitHub |
|---|---|
| `$(System.AccessToken)` | `secrets.GITHUB_TOKEN` |
| `##vso[task.setvariable]` | `>> $GITHUB_OUTPUT` / `>> $GITHUB_ENV` |
| `##vso[...;isSecret=true]` (docker-hub-auth.sh) | `::add-mask::` — the script is now CI-aware (emitting the ##vso line on GH would have **printed** the secret) |
| `Cache@2` monthly CVE key | `actions/cache@v4` + `date +%Y%m` key |
| `DownloadSecureFile@1` (secret.pgp) | base64 secret `PGP_SECRET_KEY_B64`, decoded in-step |
| `PublishTestResults@2` | `actions/upload-artifact` of `**/TEST*.xml` on failure |
| PR-vs-branch matrix subsets (3 jobs per OS) | one dynamic matrix per OS from the `setup` job |
| `free-host-disk.sh` (`target: host`) | runs normally; `AGENT_ISSELFHOSTED=1` makes it a no-op (Ubicloud VMs have no Azure bloat to reclaim) |
| git tag push (persistCredentials) | `permissions: contents: write` on `release_ror` — **no SSH deploy key needed** |

## Deliberately dropped (and why)

- **DISK_PROBE** — Azure host-disk recon; irrelevant on Ubicloud VMs.
- **SUPERSEDE_GUARD** + `ci/stale-azure-pipeline-runs-canceler.sh` — native `concurrency`.
- **Docker pre-clean / always()-reap steps** from the Azure IT template — both Ubicloud and
  GH-hosted runners are ephemeral (fresh VM per job); there are no leftovers to reap and no
  sibling jobs sharing a daemon. `run-pipeline.sh`'s SIGTERM trap still reaps on cancel.
- **Sharded-log artifact publishing** (`IT_PARALLELISM > 1`) — env var passes through; re-add
  an upload-artifact step for `integration-tests/build/sharded-logs` if you raise parallelism.

## Before first run — do these

1. **Install the Ubicloud GitHub App** on the repo (Linux jobs). Windows needs nothing —
   GitHub-hosted. Without Ubicloud, every Linux job queues forever.
2. **Set secrets/variables**: `ci/github/set-secrets.sh` (17 secrets + 8 variables, see SECRETS.md).
3. **Smoke-run**: `workflow_dispatch` → `run_all_tests_on_linux` on a branch validates
   toolchains_verify + unit + full Linux IT without touching release paths. Watch the first IT
   leg: testcontainers inside the `container:` job must reach the host Docker daemon
   (Actions auto-mounts `/var/run/docker.sock`; Ubicloud provides host Docker).
4. Azure is already trigger-disabled; it remains manually runnable from the Azure DevOps UI
   as a fallback until you delete `azure-pipelines.yml` + `ci/azure-templates/`.
