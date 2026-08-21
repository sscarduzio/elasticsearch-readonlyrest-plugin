#!/bin/bash
set -euo pipefail

# Runs every convention check against one pull request, in name order.
#
# Each check is its own file in checks/, so adding a convention means adding a file. This runner only
# fetches the pull request data once and reports the results; every check reads what it needs from the
# files below and keeps its own logic visible.
#
#   PR_JSON_FILE        the pull request object (title, body, labels, changed_files, ...)
#   PR_FILES_FILE       the changed file names, one per line (see the truncation note below)
#   PR_BUILD_DIFF_FILE  the diffs of the changed build.gradle files
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

# The data goes in files, not in the environment: a big pull request produces hundreds of kilobytes,
# and an environment variable over 128 KB makes every later command fail to start with "Argument list
# too long". (PR #1313 changed 4,590 files.)
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
export PR_NUMBER="$PR"
export PR_JSON_FILE="$WORK_DIR/pr.json"
export PR_FILES_FILE="$WORK_DIR/files"
export PR_BUILD_DIFF_FILE="$WORK_DIR/build-diff"

gh api "repos/$REPO/pulls/$PR" > "$PR_JSON_FILE"
# One crawl of the files endpoint, then derive both views locally. Paginating it twice cost ~17s per
# crawl on PR #1313 and twice the rate limit, for the same data.
#
# NOTE: GitHub caps this endpoint at 3,000 files even with --paginate, so for a very large pull
# request this list is INCOMPLETE. `.changed_files` in the JSON above is the true count; a check that
# needs completeness must compare the two and decide what to do (see 20-changelog-entry.sh).
gh api "repos/$REPO/pulls/$PR/files" --paginate > "$WORK_DIR/files.json"
jq -r '.[].filename' "$WORK_DIR/files.json" > "$PR_FILES_FILE"
jq -r '.[] | select(.filename | test("(^|/)build\\.gradle$")) | .patch // ""' \
  "$WORK_DIR/files.json" > "$PR_BUILD_DIFF_FILE"

failed=0
# Reporting only. `fail` records the failure and lets the remaining checks still run, so one run
# reports everything that is wrong instead of only the first thing.
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
