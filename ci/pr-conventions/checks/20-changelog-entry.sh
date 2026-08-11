# A change a client can see needs a changelog entry in the description.
# Escape hatch: the `no-changelog` label, for a refactor, a build change or a test.

: "${PR_JSON_FILE:?this check is sourced by ci/pr-conventions/run.sh}"
: "${PR_FILES_FILE:?this check is sourced by ci/pr-conventions/run.sh}"
: "${PR_BUILD_DIFF_FILE:?this check is sourced by ci/pr-conventions/run.sh}"

body=$(jq -r '.body // ""' "$PR_JSON_FILE")
labels=$(jq -r '.labels[].name' "$PR_JSON_FILE")

# Production code: a change here can reach a client. Test sources, CI files, build logic and docs do not.
PRODUCTION_PATHS='^(core|audit|ror-tools|ror-tools-core|es[0-9]+x|es[0-9]+-base)/src/main/'
# A changed dependency also reaches the client (it ships in the plugin zip), and it is the most common
# changelog entry we write: a CVE fix. Match only dependency declarations, so that a change to the
# plugins block or to compiler options does not ask for a changelog entry.
DEPENDENCY_LINE='^[+-][[:space:]]*(api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)[[:space:](]'

production_changed=$(grep -cE "$PRODUCTION_PATHS" "$PR_FILES_FILE" || true)
dependencies_changed=$(grep -cE "$DEPENDENCY_LINE" "$PR_BUILD_DIFF_FILE" || true)

# GitHub caps the changed-files endpoint at 3,000 entries, and this repository has pull requests above
# it (#1313 changed 4,590 files). A truncated list cannot prove that no production file changed, so
# treat it as changed and let the label be the way out. Failing open here would silently let the
# biggest changes through, which are the ones most likely to need a changelog entry.
changed_total=$(jq -r '.changed_files' "$PR_JSON_FILE")
# awk, not `wc -l`: wc counts NEWLINES, so a last line without one is not counted and a
# one-file pull request would look truncated.
files_listed=$(awk 'END{print NR}' "$PR_FILES_FILE")
truncated=false
if [ "$changed_total" -gt "$files_listed" ]; then
  truncated=true
  echo "note: the changed-file list is truncated ($files_listed of $changed_total); assuming production code changed"
fi

if [ "$truncated" = false ] && [ "$production_changed" -eq 0 ] && [ "$dependencies_changed" -eq 0 ]; then
  pass "changelog: not needed (no production sources or dependencies changed)"
elif grep -qx 'no-changelog' <<<"$labels"; then
  pass "changelog: skipped ('no-changelog' label)"
# Both documented forms count: the YAML entry of readonlyrest-docs/changelog/<version>.yaml, and the
# emoji phrase of the `ror-dev-process` skill (which is how the YAML renders in changelog.md).
# The YAML form asks for one COMPLETE entry. EVERY `type:` line starts a new entry and resets the
# state, including an unsupported one, so an incomplete entry cannot be completed by the keys of the
# entry that follows it.
# `window` = how many lines after a `type:` line its `components:`/`text:` may sit on. Widen it if
# the changelog format ever grows more keys per entry.
elif awk -v window=3 '
      /^[[:space:]]*-?[[:space:]]*type:/ {
        found=0; c=0; t=0
        if ($0 ~ /^[[:space:]]*-?[[:space:]]*type:[[:space:]]*(security|new|fix|enhancement)[[:space:]]*$/) {
          found=NR
        }
        next
      }
      found && NR<=found+window && /components:/ { c=1 }
      found && NR<=found+window && /text:[[:space:]]*[^[:space:]]/ { t=1 }
      c && t { complete=1 }
      END { exit !complete }' <<<"$body" \
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
