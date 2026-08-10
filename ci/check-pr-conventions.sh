#!/bin/bash
set -euo pipefail

# Checks the two PR conventions the team used to check by hand in review:
#
#   1. the title carries the Jira key, e.g. "[RORDEV-1234] Short summary"
#   2. a change to production sources has a changelog entry in the description
#
# Escape hatches: the `no-jira` and `no-changelog` labels.
#
# Run it before you open the pull request, or on an open one:
#   ci/check-pr-conventions.sh 1234
#
# It needs the `gh` CLI, authenticated.

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <pull request number>" >&2
  exit 2
fi
PR="$1"
REPO="${GH_REPO:-sscarduzio/elasticsearch-readonlyrest-plugin}"

# Production code: a change here can reach a client, so it needs a changelog entry. Test sources, CI
# files, build logic and docs do not.
PRODUCTION_PATHS='^(core|audit|ror-tools|ror-tools-core|es[0-9]+x|es[0-9]+-base)/src/main/'
# A changed dependency also reaches the client (it ships in the plugin zip), and it is the most common
# changelog entry we write: a CVE fix. Match only dependency declarations, so that a change to the
# plugins block or to compiler options does not ask for a changelog entry.
DEPENDENCY_LINE='^[+-][[:space:]]*(api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)[[:space:](]'

data=$(gh api "repos/$REPO/pulls/$PR" --jq '{title,body,labels:[.labels[].name]}')
title=$(jq -r '.title' <<<"$data")
body=$(jq -r '.body // ""' <<<"$data")
labels=$(jq -r '.labels[]' <<<"$data")
files=$(gh api "repos/$REPO/pulls/$PR/files" --paginate --jq '.[].filename')
dependency_diff=$(gh api "repos/$REPO/pulls/$PR/files" --paginate \
  --jq '.[] | select(.filename | test("(^|/)build\\.gradle$")) | .patch // ""')

has_label() { grep -qx "$1" <<<"$labels"; }
failed=0
fail() { echo "FAIL: $1"; failed=1; }
pass() { echo "ok:   $1"; }

# --- 1. Jira key in the title -------------------------------------------------------------------
if has_label no-jira; then
  pass "title: skipped ('no-jira' label)"
elif [[ $title =~ ^\[RORDEV-[0-9]+\][[:space:]]+.+ ]]; then
  pass "title: carries the Jira key"
else
  fail "the title must start with the Jira key, e.g. '[RORDEV-1234] $title'
      Add the 'no-jira' label if this change does not need a ticket."
fi

# --- 2. Changelog entry for production changes --------------------------------------------------
production_changed=$(grep -cE "$PRODUCTION_PATHS" <<<"$files" || true)
dependencies_changed=$(grep -cE "$DEPENDENCY_LINE" <<<"$dependency_diff" || true)
if [ "$production_changed" -eq 0 ] && [ "$dependencies_changed" -eq 0 ]; then
  pass "changelog: not needed (no production sources or dependencies changed)"
elif has_label no-changelog; then
  pass "changelog: skipped ('no-changelog' label)"
# Both documented forms count: the YAML entry of readonlyrest-docs/changelog/<version>.yaml, and the
# emoji phrase of the `ror-dev-process` skill (which is how the YAML renders in changelog.md).
# The YAML form asks for a complete entry — a `type:` line with a real type, then `components:` and
# `text:` within the following lines — so that three unrelated lines elsewhere in the description
# cannot pass the check by accident.
elif awk '
      /^[[:space:]]*-?[[:space:]]*type:[[:space:]]*(security|new|fix|enhancement)[[:space:]]*$/ { found=NR }
      found && NR>found && NR<=found+3 && /components:/ { c=1 }
      found && NR>found && NR<=found+3 && /text:/       { t=1 }
      END { exit !(c && t) }' <<<"$body" \
  || grep -qE '\*\*(Security Fix|New|Enhancement|Fix)\*\*[[:space:]]*\((ES|KBN)\)' <<<"$body"; then
  pass "changelog: entry found in the description"
else
  fail "this pull request changes production sources ($production_changed file(s)) or dependencies
      ($dependencies_changed line(s)), so the description needs a changelog entry at the top, in the format of
      beshu-tech/readonlyrest-docs/changelog/<version>.yaml:

        - type: fix          # security | new | fix | enhancement
          components: [es]
          text: \"Short sentence for the release notes\"

      Add the 'no-changelog' label if this change cannot be seen by a client."
fi

exit $failed
