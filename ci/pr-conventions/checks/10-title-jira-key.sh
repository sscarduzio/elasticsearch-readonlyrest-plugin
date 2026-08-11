# The title must carry the Jira key: "[RORDEV-1234] Short summary".
# Escape hatch: the `no-jira` label, for a change that does not need a ticket.
#
# Sourced by run.sh; uses PR_TITLE and the has_label / pass / fail helpers.

if has_label no-jira; then
  pass "title: skipped ('no-jira' label)"
elif [[ $PR_TITLE =~ ^\[RORDEV-[0-9]+\][[:space:]]+.+ ]]; then
  pass "title: carries the Jira key"
else
  fail "the title must start with the Jira key, e.g. '[RORDEV-1234] $PR_TITLE'
      Add the 'no-jira' label if this change does not need a ticket."
fi
