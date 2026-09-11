# Self-hosted runners for this repo

The release path runs on the ReadonlyREST build host rather than on a paid cloud runner. The jobs
are long, IO-bound and low-concurrency: gradle assembly plus uploads. Nothing else waits on them,
so a slow runner costs wall time but blocks no other job.

**Do not move a job here if another run polls it.** The pool has few slots. A queued job holds up
every runner that waits for its result, and those runners may be paid.

Jobs that need a self-hosted runner:

| Workflow | Job | Shape |
|---|---|---|
| `ci.yml` | `upload_pre_ror` | 4-leg matrix, pre-release only |
| `ci.yml` | `release_ror` | 4-leg matrix, release only |
| `ci.yml` | `publish_mvn` | seconds, after `release_ror` |
| `publish-pre-builds.yml` | `publish` | manual, long build-and-push |
| `mirror-es-libs.yml` | `mirror` | manual, short |

**Size the pool on the slowest leg, not the average, and measure before you change it.** Leg times
differ by more than an order of magnitude between ES majors, and they move when the build changes.
To get the current numbers:

```bash
gh run list --repo sscarduzio/elasticsearch-readonlyrest-plugin \
  --workflow ci.yml --limit 50 --json databaseId \
  --jq '.[].databaseId' |
  xargs -I{} gh run view {} --repo sscarduzio/elasticsearch-readonlyrest-plugin \
    --json jobs --jq '.jobs[] | select(.name|startswith("release_")) |
      "\(.name) \(.startedAt) \(.completedAt)"'
```

`release_ror` sets `max-parallel: 2`, which does not create fixed pairs — Actions starts a queued
leg as soon as a slot frees — so read a release's occupancy as first leg start to last leg finish,
not as the sum of the legs.

## The selector

`runs-on: [self-hosted, Linux, X64]` — the same generic selector `readonlyrest_kbn` uses. No custom
label. Runner scope already isolates the pool: a repo-level runner only ever receives jobs from the
repo it is registered to, so a label adds nothing.

This repo lives under the `sscarduzio` user account, not the `beshu-tech` organisation, so the
existing org-level runners (`gh-beshu-1..6`, registered to `https://github.com/beshu-tech`) cannot
serve it. Registration below creates repo-level runners the same way `gh-ror-kbn-1..6` are
registered to `https://github.com/sscarduzio/readonlyrest_kbn`.

## The build host

Runners are unprivileged LXC containers under Incus, in the `github-ci` project, one runner per
container. Everything below runs as root on the host.

Existing containers in that project:

```
gh-base                 STOPPED   template, clone this
gh-beshu-1 .. gh-beshu-6   RUNNING   org runners  (github.com/beshu-tech)
gh-ror-kbn-1 .. -6         RUNNING   repo runners (sscarduzio/readonlyrest_kbn)
sccache-minio              RUNNING   shared cache
```

They share the project's `default` profile: `limits.cpu: 1-15`, `limits.memory: 14GB`,
`security.nesting: true`, `security.privileged: true`, 40 GB root disk on `home-pool`.

### Capacity warning

The host is a Ryzen 7 3700X: 8 cores / 16 threads, 62 GB RAM. Fourteen containers each entitled to
15 threads and 14 GB is heavy oversubscription, and it is the main reason a Kibana E2E leg takes
50–56 min here against 15–20 min on an 8-vCPU cloud runner. **Add ES runners only alongside a
capacity decision**: either cap the Kibana pool (stop 2–3 of `gh-ror-kbn-*`), or lower
`limits.cpu` per container so the pools cannot all claim the whole machine.

Two ES runners is the right number: the release matrices are capped at `max-parallel: 2`.

## Registering a runner

Repeat for `N` in `1 2`, from the host:

```bash
# 1. clone the template
incus copy --project github-ci gh-base gh-ror-es-$N
incus start --project github-ci gh-ror-es-$N

# 2. a registration token is single-use and expires in an hour — get a fresh one per runner
TOKEN=$(gh api -X POST \
  repos/sscarduzio/elasticsearch-readonlyrest-plugin/actions/runners/registration-token \
  -q .token)

# 3. configure and install the service inside the container
incus exec --project github-ci gh-ror-es-$N -- sudo -u runner bash -lc "
  cd /home/runner/actions-runner &&
  ./config.sh \
    --url https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin \
    --token $TOKEN \
    --name gh-ror-es-$N \
    --work _work \
    --unattended --replace"
incus exec --project github-ci gh-ror-es-$N -- bash -lc \
  "cd /home/runner/actions-runner && ./svc.sh install runner && ./svc.sh start"
```

`config.sh` adds `self-hosted`, `Linux` and `X64` on its own, which is the whole selector the
workflows use. Pass no `--labels`.

Verify:

```bash
gh api repos/sscarduzio/elasticsearch-readonlyrest-plugin/actions/runners \
  -q '.runners[] | "\(.name)\t\(.status)\t\([.labels[].name]|join(","))"'
```

## Requirements inside the container

- The `runner` user must be in the `docker` group; the release jobs build and push images.
- Disk is the binding constraint, roughly 1.5 GB of base image per ES version. The 40 GB root disk
  in the profile is enough for two runners only because `ci/free-host-disk.sh` prunes between legs.
- Shared host, so the CI scripts must not sweep the whole Docker daemon. They detect the box by the
  marker file `/etc/ror-shared-docker-host` and downgrade every prune to dangling layers and build
  cache. Write the marker when you provision the runner:

  ```bash
  incus exec --project github-ci gh-ror-es-$N -- sh -c \
    'echo "Runners for more than one repo share this box." > /etc/ror-shared-docker-host'
  ```

  Without it, a retry of a release leg would kill the Kibana runners' in-flight ELK stacks.
- Three runners, and `release_ror` / `upload_pre_ror` keep `max-parallel: 2`. A release then never
  takes every slot, so the pre-build that two repos wait on always finds one.
