# The `ror-es` self-hosted runners

The release and upload jobs run on the ryzen box, not on Ubicloud. Until the runners below exist,
those jobs queue forever — GitHub never reports "no runner", it just waits.

Jobs that need a `ror-es` runner:

| Workflow | Job | Ubicloud minutes/month it used |
|---|---|---|
| `ci.yml` | `upload_pre_ror` (4 legs) | ~3800 |
| `ci.yml` | `release_ror` (4 legs) | ~2200 |
| `ci.yml` | `publish_mvn` | small |
| `publish-pre-builds.yml` | `publish` | manual, long |
| `mirror-es-libs.yml` | `mirror` | manual, short |

Everything else stays where it is.

## Why a `ror-es` label and not the plain `self-hosted, Linux, X64` the kbn repo uses

The box already runs two other fleets: `gh-beshu-1..6` for the **beshu-tech org**
(`self-hosted,Linux,X64,k3s`) and `gh-ror-kbn-1..5` for the **readonlyrest_kbn repo**
(`self-hosted,Linux,X64,k3s,incus`).

This repo lives under the `sscarduzio` **user** account, so the org runners cannot serve it. It
needs its own repo-level runners. The extra `ror-es` label keeps them a separate pool that can be
resized without touching the kbn fleet, and stops an ES release leg from landing on a runner that
is busy with a kbn ELK stack.

## Sizing: two runners

The release legs are IO-bound — gradle assembly, then S3, Docker Hub, Maven Central and
GitHub-release uploads. They are slow because of bytes on the wire, not because of CPU, so more
parallelism buys little and costs memory.

- **2 runners.** The matrices are capped at `max-parallel: 2` to match. A third would compete with
  the kbn fleet for the same 64 GB.
- **~4 GB RAM each** is enough: no ES cluster starts in these jobs.
- **Disk is the real constraint.** Each ES version pulls a ~1.5 GB base image. Keep 100 GB or more
  free for docker; the jobs prune their own leftovers between versions but never the whole daemon.
- Give each runner its **own install directory**. One runner runs one job at a time, and each
  runner keeps its own `_work` tree, which is what stops two release legs from sharing a workspace.

## Register them

Run once per runner, `N` in `1 2`. Each `config.sh` call needs a fresh token — they expire after
one hour and are single-use.

```bash
REPO=sscarduzio/elasticsearch-readonlyrest-plugin
VERSION=2.337.0   # match the version the existing runners on the box report

for N in 1 2; do
  DIR="$HOME/actions-runners/gh-ror-es-$N"
  mkdir -p "$DIR" && cd "$DIR"

  curl -fsSL -o actions-runner.tar.gz \
    "https://github.com/actions/runner/releases/download/v${VERSION}/actions-runner-linux-x64-${VERSION}.tar.gz"
  tar xzf actions-runner.tar.gz && rm actions-runner.tar.gz

  TOKEN=$(gh api -X POST "repos/${REPO}/actions/runners/registration-token" -q .token)

  ./config.sh \
    --url "https://github.com/${REPO}" \
    --token "$TOKEN" \
    --name "gh-ror-es-$N" \
    --labels ror-es \
    --work _work \
    --unattended --replace

  sudo ./svc.sh install && sudo ./svc.sh start
done
```

`--labels ror-es` adds to the defaults; the runner still reports `self-hosted`, `Linux` and `X64`,
so the `[self-hosted, Linux, X64, ror-es]` selector in the workflows matches.

## Before the first run

- The runner user must be in the `docker` group. `upload_pre_ror`, `release_ror` and `publish_mvn`
  run inside the `beshultd/ror-ci-toolchains` container, which the runner starts on the host
  daemon and into which it mounts the docker socket.
- Docker Hub and S3 credentials come from repo secrets, exactly as on Ubicloud. Nothing on the box
  needs to hold them.

## Check

```bash
gh api repos/sscarduzio/elasticsearch-readonlyrest-plugin/actions/runners \
  -q '.runners[] | "\(.name)  \(.status)  \([.labels[].name] | join(","))"'
```

Two `gh-ror-es-*` rows, `online`, carrying `ror-es`, means the jobs can be scheduled.

## Remove one

```bash
TOKEN=$(gh api -X POST repos/sscarduzio/elasticsearch-readonlyrest-plugin/actions/runners/remove-token -q .token)
cd "$HOME/actions-runners/gh-ror-es-1"
sudo ./svc.sh stop && sudo ./svc.sh uninstall
./config.sh remove --token "$TOKEN"
```

## Rollback

Put `ubicloud-standard-4` back in the five `runs-on:` lines. Nothing else in this change depends on
the runner: `ROR_SHARED_DOCKER_HOST` simply goes unset, and every guarded cleanup falls back to the
host-wide sweep it did before.
