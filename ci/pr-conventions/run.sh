#!/bin/bash
set -euo pipefail

# Runs every convention check against one pull request, in name order.
#
# Each check is its own file in checks/, so adding a convention means adding a file — nothing here
# changes. The pull request data is fetched once and exported, so a check never makes its own API call.
#
# Run it before you open the pull request, or on an open one:
#   ci/pr-conventions/run.sh 1234
#
# It needs the `gh` CLI, authenticated.

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <pull request number>" >&2
  exit 2
fi
PR="$1"
REPO="${GH_REPO:-sscarduzio/elasticsearch-readonlyrest-plugin}"
CHECKS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/checks"

data=$(gh api "repos/$REPO/pulls/$PR" --jq '{title,body,labels:[.labels[].name]}')
export PR_NUMBER="$PR"
PR_TITLE=$(jq -r '.title' <<<"$data"); export PR_TITLE
PR_BODY=$(jq -r '.body // ""' <<<"$data"); export PR_BODY
PR_LABELS=$(jq -r '.labels[]' <<<"$data"); export PR_LABELS
# The file list and the build diffs go in files, not in the environment: a big pull request produces
# hundreds of kilobytes, and an environment variable over 128 KB makes every later command fail to
# start with "Argument list too long". (PR #1313 changed 4,590 files.)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
export PR_FILES_FILE="$WORK_DIR/files"
export PR_BUILD_DIFF_FILE="$WORK_DIR/build-diff"
gh api "repos/$REPO/pulls/$PR/files" --paginate --jq '.[].filename' > "$PR_FILES_FILE"
# Only the build files' diffs: a check that cares about dependencies needs the changed lines, not just
# the file names.
gh api "repos/$REPO/pulls/$PR/files" --paginate \
  --jq '.[] | select(.filename | test("(^|/)build\\.gradle$")) | .patch // ""' > "$PR_BUILD_DIFF_FILE"

failed=0
# Helpers every check uses. `fail` records the failure and lets the remaining checks still run, so one
# run reports everything that is wrong instead of only the first thing.
has_label() { grep -qx "$1" <<<"$PR_LABELS"; }
pass() { echo "ok:   $1"; }
fail() { echo "FAIL: $1"; failed=1; }

shopt -s nullglob
checks=("$CHECKS_DIR"/*.sh)
if [ ${#checks[@]} -eq 0 ]; then
  echo "no checks found in $CHECKS_DIR" >&2
  exit 2
fi
for check in "${checks[@]}"; do
  # shellcheck source=/dev/null
  . "$check"
done

exit $failed
