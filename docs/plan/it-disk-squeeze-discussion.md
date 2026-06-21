# ROR integration-test CI: disk-squeeze discussion (PR #1263)

**Goal:** keep the 10 FREE Microsoft-hosted runners (resizing is off the table — it forfeits the
only reason we use Azure). The ES 8.x legs fail `No space left on device` on a runner whose Docker
overlay starts ~81% full (~15GB free). This is a *capacity* problem, not a regression — proven by
A/B (master peaks 8 concurrent images vs this PR's 3). The levers below raise the ceiling without
touching the runner size.

## Levers, ranked by payoff

| # | Lever | Payoff | Status |
|---|-------|--------|--------|
| 1 | Generator emits COPY/RUN/USER in declaration order → heavy layers SHARED | **~13GB/leg → ~1.3GB** | **DONE** (`ac467dbd`), measured |
| 2 | `target: host` reclaim via a bare pre-step | **~25GB** on the host fs | probe shipped (`3e050a30`), awaiting chart |
| 3 | ES disk watermarks → 99% | n/a — threshold already fully disabled | NO-OP (already covered) |
| 4 | BuildKit bind-mount the 91MB plugin zip (don't bake) | −91MB/leg | proposal |

### #1 — Ordered image steps (the load-bearing fix) — DONE

**Root cause:** `DockerImageCreator` collected COPYs from a `Set` (order lost) and emitted them all
*before* the RUNs. So per-config files (`readonlyrest.yml`, `elasticsearch.yml`) always landed at
the TOP of the Dockerfile → the config-independent install+patch layers (~1.2GB) were rebuilt fresh
for every distinct config → each suite's image was fully UNIQUE → ~13GB/leg of duplicated layers.

My earlier "config-last" source reorder (b5f36aaf) was **inert** because the generator discarded
declaration order regardless. The fix had to be in the generator:

- `DockerImageDescription`: one ordered `steps: Seq[Command]` (new `Command.Copy`) replacing
  `copyFiles: Set` + `runCommands: Seq`. `copyFile` dedups by destination (old Set semantics).
- `DockerImageCreator.applyStepsFrom`: fold steps in declaration order, merging only ADJACENT Runs
  into one `&&` RUN; COPY/USER are layer boundaries.

**Measured (OrbStack, es818x, ClusterApiSuite + FipsSslSuite):** 3 distinct-config `ror-it-es`
images each report `SHARED 1.24GB / UNIQUE 0B`. Predicted 152.8MB→52KB UNIQUE; actual 0B (configs
differ only in tiny YAML COPY layers). Suites pass. Per-leg: ~13.6GB → ~1.3GB.

### #2 — Host-side reclaim (`target: host`) — probe shipped

The IT legs run INSIDE the `ror-toolchains` container, so the fat dirs on the Azure VM **host** fs
aren't visible there — which is why the earlier in-container `rm` freed nothing. The real bytes
(~25GB on MS-hosted ubuntu: dotnet ~23GB, android ~9GB, hostedtoolcache ~8GB, ghc/swift/…) sit on
the host, on the same device as `/var/lib/docker`.

**Probe** (`ci/probe-host-disk.sh`, gated stage, `runDiskProbe=true`): resolves docker's device,
`du -skx` an allowlist under per-dir timeout (no cross-device walk), ranks desc, drops <512MB, flags
`SAME_AS_DOCKER`, sums only same-device reclaim, `exit 1` (recon only). The chart decides exactly
which dirs a real `target: host` reclaim step removes — we delete only what's fat AND on docker's fs.

**Why not `/mnt`:** on hosted runners `/mnt` is the SMALL ~14GB resource disk, not the big one.
Moving Docker's data-root there is *worse*. Confirmed trap.

### #3 — Watermarks → 99% — ALREADY COVERED (no-op here)

`baseEsConfigBuilder` (Elasticsearch.scala:287) already sets
`cluster.routing.allocation.disk.threshold_enabled: false`, which disables the disk allocator
ENTIRELY — ES never flips indices read-only on disk pressure regardless of watermark values. Setting
low/high/flood_stage = 99% on top would be dead config (the watermarks are only consulted when the
threshold is enabled). The 99% idea is the right move only on a system that *must* keep the threshold
on; ours already turns it off, which is strictly stronger. **No change needed.**

### #4 — BuildKit bind-mount plugin (proposal)

Bake-time `COPY plugin.zip` adds a 91MB layer per image. A BuildKit `RUN --mount=type=bind` installs
from the zip without persisting it in a layer. −91MB/leg. Lower priority than #1/#2.

## Rejected (traps avoided)

- **Resize runners** — forfeits the free 10. Off the table by decree.
- **`/mnt` as data-root** — small disk on hosted; worse.
- **Sharding for disk** — K concurrent builds *raise* peak disk. Sharding is a throughput lever, not
  a disk lever. (Stable tags do make sharding more layer-efficient, but that's orthogonal.)
- **Background image sweeper** — raced concurrent builds → "unknown parent image" corruption. Reverted.
- **shardCount-conditional networking band-aid** — the multi-node failures were a staging-dir race,
  not a network regression. Fixed properly (per-build staging). No conditional networking.

## Verification plan

- [x] #1 layer-sharing measured locally (0B UNIQUE) + suites pass
- [ ] #1 confirmed on CI: ES 8.x legs (es80x/82x/83x/84x) fit on hosted runner (build 10539)
- [ ] #2 probe chart from a real Azure VM (build 10540) → pick reclaim dirs
- [x] #3 no-op — disk threshold already disabled (stronger than watermark=99%)
