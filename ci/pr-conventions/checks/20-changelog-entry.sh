# A change a client can see needs a changelog entry in the description.
# Escape hatch: the `no-changelog` label, for a refactor, a build change or a test.
#
# Sourced by run.sh; uses PR_FILES_FILE, PR_BUILD_DIFF_FILE, PR_BODY and the has_label / pass / fail
# helpers. The two big inputs arrive as files, not environment variables (see run.sh).

# Production code: a change here can reach a client. Test sources, CI files, build logic and docs do not.
PRODUCTION_PATHS='^(core|audit|ror-tools|ror-tools-core|es[0-9]+x|es[0-9]+-base)/src/main/'
# A changed dependency also reaches the client (it ships in the plugin zip), and it is the most common
# changelog entry we write: a CVE fix. Match only dependency declarations, so that a change to the
# plugins block or to compiler options does not ask for a changelog entry.
DEPENDENCY_LINE='^[+-][[:space:]]*(api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)[[:space:](]'

production_changed=$(grep -cE "$PRODUCTION_PATHS" "$PR_FILES_FILE" || true)
dependencies_changed=$(grep -cE "$DEPENDENCY_LINE" "$PR_BUILD_DIFF_FILE" || true)

if [ "$production_changed" -eq 0 ] && [ "$dependencies_changed" -eq 0 ]; then
  pass "changelog: not needed (no production sources or dependencies changed)"
elif has_label no-changelog; then
  pass "changelog: skipped ('no-changelog' label)"
# Both documented forms count: the YAML entry of readonlyrest-docs/changelog/<version>.yaml, and the
# emoji phrase of the `ror-dev-process` skill (which is how the YAML renders in changelog.md).
# The YAML form asks for one COMPLETE entry — a `type:` line with a real type, then `components:` and
# `text:` within the following lines — so that unrelated lines elsewhere cannot pass the check between
# them.
elif awk '
      /^[[:space:]]*-?[[:space:]]*type:[[:space:]]*(security|new|fix|enhancement)[[:space:]]*$/ {
        found=NR; c=0; t=0; next
      }
      found && NR<=found+3 && /components:/ { c=1 }
      found && NR<=found+3 && /text:[[:space:]]*[^[:space:]]/ { t=1 }
      c && t { complete=1 }
      END { exit !complete }' <<<"$PR_BODY" \
  || grep -qE '\*\*(Security Fix|New|Enhancement|Fix)\*\*[[:space:]]*\((ES|KBN)\)' <<<"$PR_BODY"; then
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
