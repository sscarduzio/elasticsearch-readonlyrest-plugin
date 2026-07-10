# CI secrets to port: Azure Pipelines → GitHub Actions

Pre-analysis for the Ubicloud/Blacksmith CI port. Every secret the current
`azure-pipelines.yml` + `ci/azure-templates/*.yml` reference, where it's used,
and how it maps to GitHub Actions. **No values are in this repo** — set them with
`ci/github/set-secrets.sh` (placeholders) or the GitHub UI.

## How Azure vars map to GitHub Actions

Azure has one flat namespace (`$(NAME)`) spanning three kinds of thing. GitHub
splits them:

| Azure form | Kind | GitHub Actions target |
|---|---|---|
| `$(NAME)` secret pipeline var | sensitive | **Repository secret** (`secrets.NAME`) |
| `$(NAME)` plain pipeline var | non-sensitive config | **Repository variable** (`vars.NAME`) |
| `DownloadSecureFile@1` | secret file | **Repository secret** holding base64 of the file |
| `$(System.AccessToken)` | Azure-issued | `secrets.GITHUB_TOKEN` (auto, don't create) |

## Secret inventory (sensitive → repository secrets)

These MUST be repository **secrets** (masked in logs). Grouped by function.

### S3-compatible object stores (2 buckets: libs + artifacts)

The pipeline talks to two S3-compatible stores. `LIBS` = shared ES dependency
jars (read in tests, written by `newes/*` branches). `ARTIFACTS` = built ROR
plugin binaries (release/pre-release uploads).

| Secret | Purpose | Stages that consume it |
|---|---|---|
| `ROR_LIBS_STORE_ACCESS_KEY_ID` | libs bucket key id | ES_S3_UP, REQUIRED_CHECKS, TEST(IT), UPLOAD_PRE_ROR, RELEASE_ROR* |
| `ROR_LIBS_STORE_ACCESS_KEY_SECRET` | libs bucket secret | same |
| `ROR_ARTIFACTS_STORE_ACCESS_KEY_ID` | artifacts bucket key id | ES_S3_UP, UPLOAD_PRE_ROR, RELEASE_ROR* |
| `ROR_ARTIFACTS_STORE_ACCESS_KEY_SECRET` | artifacts bucket secret | same |

The **non-secret** halves of these store configs (endpoint URL, region, bucket
name, path prefix) are config, not secrets → see the variables table below.
(They're low-sensitivity; if you'd rather keep the whole store config together,
nothing breaks if you make them secrets too — just harder to eyeball in the UI.)

### Docker registry / Docker Hub

| Secret | Purpose | Stages |
|---|---|---|
| `DOCKER_REGISTRY_USER` | push ROR images (release) + push toolchains image | BUILD_TOOLCHAINS_IMAGE, UPLOAD_PRE_ROR, RELEASE_ROR* |
| `DOCKER_REGISTRY_PASSWORD` | ditto | same |
| `DOCKER_HUB_USER` | authenticated **pulls** (testcontainers rate-limit) | TEST (unit + IT) |
| `DOCKER_HUB_RO_TOKEN` | read-only Docker Hub token for pulls | TEST (unit + IT) |

`DOCKER_HUB_*` is consumed by `ci/docker-hub-auth.sh`, which is a **no-op when
either is unset** — so tests still run (anonymous, rate-limited) if you skip
these initially. `DOCKER_REGISTRY_*` is required for any release/upload stage.

### CVE / dependency-check (OWASP)

| Secret | Purpose | Stages |
|---|---|---|
| `NVD_API_KEY` | NVD feed API key (faster CVE DB pulls) | OPTIONAL_CHECKS (cve_check) |
| `OSS_INDEX_USERNAME` | Sonatype OSS Index user | OPTIONAL_CHECKS |
| `OSS_INDEX_PASSWORD` | Sonatype OSS Index password | OPTIONAL_CHECKS |

**Fork-safe by design:** the Azure step only exports these when
`System.PullRequest.IsFork == False`. On GitHub, secrets are already withheld
from fork PRs automatically, so no extra guard is needed — but keep `cve_check`
as `continue-on-error` (it already is) so a fork PR without the key doesn't fail red.

### Maven Central / Sonatype publishing

| Secret | Purpose | Stages |
|---|---|---|
| `MAVEN_REPO_USER` | Sonatype OSSRH user | PUBLISH_MVN_ARTIFACTS* |
| `MAVEN_REPO_PASSWORD` | Sonatype OSSRH password | PUBLISH_MVN_ARTIFACTS* |
| `MAVEN_STAGING_PROFILE_ID` | Nexus staging profile id | PUBLISH_MVN_ARTIFACTS* |
| `GPG_KEY_ID` | signing key id | PUBLISH_MVN_ARTIFACTS* |
| `GPG_PASSPHRASE` | signing key passphrase | PUBLISH_MVN_ARTIFACTS* |

### Secure files (were `DownloadSecureFile@1` → base64 secrets)

Azure stored these as "secure files" in the library. GitHub has no equivalent;
store the **base64 of the file contents** in a secret and decode in the step.

| Secret | Was | Purpose | Stages |
|---|---|---|---|
| `PGP_SECRET_KEY_B64` | `secret.pgp` | GPG private key for signing Maven artifacts | PUBLISH_MVN_ARTIFACTS* |

Create with: `base64 -w0 secret.pgp | gh secret set PGP_SECRET_KEY_B64 -R <repo>`
The workflow step writes it back: `echo "$PGP_SECRET_KEY_B64" | base64 -d > .travis/secret.pgp`
(`audit/build.gradle` reads `.travis/secret.pgp` — path verified.)

> ✅ RESOLVED: no SSH deploy key is needed. `ci/ci-lib.sh` pushes release tags via
> `git push origin` — i.e. the checkout credential. The workflow grants the
> `release_ror` job `permissions: contents: write`, so `GITHUB_TOKEN` covers it.
> (The old 2022 pipeline's `gh_deploy_key.priv` is obsolete.)

### Already present in the repo (no action)

| Secret | Status |
|---|---|
| `GITHUB_TOKEN` | auto-provided by Actions; replaces `$(System.AccessToken)` for the supersede-guard API calls |
| `CLAUDE_CODE_OAUTH_TOKEN` | already set (Claude review workflows) |
| `GH_PAT` | already set (legacy) |

## Non-sensitive config (→ repository variables, `vars.NAME`)

Not secrets — safe to expose in logs. Set as **variables**, not secrets, so
they're readable in the UI and don't clutter the secret list.

| Variable | Purpose |
|---|---|
| `ROR_LIBS_STORE_ENDPOINT_URL` | libs S3 endpoint |
| `ROR_LIBS_STORE_REGION` | libs S3 region |
| `ROR_LIBS_STORE_BUCKET` | libs bucket name |
| `ROR_LIBS_STORE_PATH_PREFIX` | libs key prefix |
| `ROR_ARTIFACTS_STORE_ENDPOINT_URL` | artifacts S3 endpoint |
| `ROR_ARTIFACTS_STORE_REGION` | artifacts S3 region |
| `ROR_ARTIFACTS_STORE_BUCKET` | artifacts bucket name |
| `ROR_ARTIFACTS_STORE_PATH_PREFIX` | artifacts key prefix |

## Count

- **17 repository secrets** (2+2 S3 keys, 4 docker, 3 CVE, 5 maven, 1 pgp file)
- **8 repository variables** (S3 store config)
- **0 to create** for `GITHUB_TOKEN` (auto)

## Azure-isms that do NOT map to a secret (handled in workflow code)

| Azure feature | Replacement |
|---|---|
| `$(System.AccessToken)` | `secrets.GITHUB_TOKEN` |
| `System.PullRequest.IsFork` | `github.event.pull_request.head.repo.fork` (or rely on GH withholding secrets from forks) |
| `##vso[task.setvariable ...]` | `>> $GITHUB_ENV` / `>> $GITHUB_OUTPUT` |
| `##vso[...;isSecret=true]` | `::add-mask::` |
| `Cache@2` (CVE DB) | `actions/cache` |
| `DownloadSecureFile@1` | base64 secret + decode step (see above) |
| `PublishTestResults@2` | `actions/upload-artifact` of `**/TEST*.xml` (or a test-reporter action) |
