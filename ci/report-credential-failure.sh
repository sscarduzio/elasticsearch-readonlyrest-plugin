#!/usr/bin/env bash

# Make a failed credential check visible without a new secret.
#
# One issue, reused. A daily job that opens a new issue every morning trains everyone to ignore it,
# so this comments on the open one instead and opens a fresh issue only when none is open.

set -euo pipefail

TITLE="Publish credentials are not working"
# No label: the repo has no CI label, and a failure handler must not fail on a missing one.

existing="$(gh issue list --state open --search "\"$TITLE\" in:title" --json number,title \
  --jq "[.[] | select(.title == \"$TITLE\")] | .[0].number // empty")"

body="The scheduled credential check failed.

Run: ${RUN_URL:-unknown}

\`publish_mvn\` cannot publish while this is red, and it fails quietly: when the version in
\`gradle.properties\` is already published the job skips and reports success, so a release is the
first thing that finds out. The job log says which credential the staging API rejected and where to
set it."

if [ -n "$existing" ]; then
  echo ">>> Issue #$existing is already open; adding a comment."
  gh issue comment "$existing" --body "$body"
else
  echo ">>> Opening an issue."
  gh issue create --title "$TITLE" --body "$body"
fi
