# Branching

## The two long-lived branches

- `master` carries the released code. Its version in `gradle.properties` is always stable (`X.Y.Z`).
- `develop` carries the code under development. Its version becomes unstable (`X.Y.Z-preN`) with the first change after a release, and stays unstable until the next release.

A release merges `develop` into `master`. Version and artifact mechanics live in the `ror-release` skill.

## Which branch does a PR target?

**Target `develop`.** Every new feature, every change of behaviour, and every bugfix for code that is not released yet goes to `develop`.

**Target `master` when the change belongs on the released code now, not at the next release.** These cases qualify:

1. **Support for a new ES version.** Customers run released ES versions, so the support ships in a patch release. Most PRs to `master` are of this kind.
2. **A fix that must ship immediately.** The released version has the bug, so the fix goes out in a patch release instead of waiting for the next feature release.
3. **A suppression of a false positive in the CVE scan.** The scan runs on both branches, so a suppression that only lives on `develop` leaves `master` alerting for a finding that is not real.
4. **A pipeline, build or publishing change.** The release runs from `master`, so the branch needs the current pipeline.
5. **Docs and examples that describe the released version.**

**A CVE fix targets `develop`.** It goes out with the next release, like any other fix. Target `master` only when the team decides to ship the fix in an immediate patch release. That decision comes first, and the branch follows it. Until the fix reaches `master`, the scan on `master` continues to report the CVE.

We merge these to `master` first because `master` must carry them as soon as they are ready. The branch produces the next release, and the pipeline and the CVE scan run on it.

Everything else targets `develop`. Two questions decide a case that is not on the list. Does the released version need the change now? Does the change leave the released code untouched? If both answers are no, the PR targets `develop`.

## After a merge to master

Merge `master` back into `develop`:

```bash
git checkout develop && git pull
git merge origin/master
```

The change must exist on both branches. Without the merge back, the next release drops it.

When the two branches have moved apart too far for one merge, open one PR per branch. Say so in both descriptions and link them.
