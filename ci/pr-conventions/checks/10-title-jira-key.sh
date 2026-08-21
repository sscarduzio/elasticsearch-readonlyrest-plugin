# The title must carry the Jira key: "[RORDEV-1234] Short summary".
# A change can close several tickets, so a run of keys is fine too, with or without spaces between
# them: "[RORDEV-1234][RORDEV-5678] Short summary".
# Escape hatch: the `no-jira` label, for a change that does not need a ticket.

: "${PR_JSON_FILE:?this check is sourced by ci/pr-conventions/run.sh}"

title=$(jq -r '.title' "$PR_JSON_FILE")
labels=$(jq -r '.labels[].name' "$PR_JSON_FILE")

# Keys, then a summary that starts with a visible character. Only plain spaces separate them: a tab
# or a newline in a title is a mistake, not a separator. The pattern lives in a variable because an
# unquoted space would end the pattern inside `[[ =~ ]]`.
jira_key_title='^(\[RORDEV-[0-9]+\] *)*\[RORDEV-[0-9]+\] +[^[:space:]]'

if grep -qx 'no-jira' <<<"$labels"; then
  pass "title: skipped ('no-jira' label)"
elif [[ $title =~ $jira_key_title ]]; then
  pass "title: carries the Jira key"
else
  fail "the title must start with the Jira key, e.g. '[RORDEV-1234] $title'
      Add the 'no-jira' label if this change does not need a ticket."
fi
