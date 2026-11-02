#!/usr/bin/env bash
# Prints the base branch of a pull request run: the base that the PR had when GitHub built HEAD, the
# merge commit under test. base_ref of the event is not enough: it names the old base when the base
# of the PR changes just before a push. The base that the PR names now is not enough either: a re-run
# tests an old HEAD, from before a later change of base.
# When a step fails, it prints base_ref and a warning that names the step.
#
# Risk: the rule takes the commit time of HEAD as the time when GitHub built HEAD. GitHub does not
# document this. A push some seconds after a change of base can get the old base, if the clock that
# sets the commit time and the clock that records the change differ by more than that.
#
# Usage: GH_TOKEN=<token> GITHUB_REPOSITORY=<owner/repo> base-branch-of-pull-request.sh <base_ref> <PR number>
set -uo pipefail

# shellcheck disable=SC2016 # GraphQL variables, not shell ones
BASE_CHANGES_QUERY='query($owner: String!, $name: String!, $pr: Int!) {
  repository(owner: $owner, name: $name) { pullRequest(number: $pr) { baseRefName
    timelineItems(itemTypes: [BASE_REF_CHANGED_EVENT], last: 100) {
      nodes { ... on BaseRefChangedEvent { createdAt previousRefName currentRefName } } } } } }'

# Reads the reply to BASE_CHANGES_QUERY on stdin. Prints the base of the PR at the time $1, in seconds
# since the epoch: the last base change up to that time names it. With no such change, the base
# before the first change does, and with no change at all, the base of the PR. Fails when the reply
# names no base.
base_at() {
  jq -er --argjson at "$1" '.data.repository.pullRequest
    | .timelineItems.nodes as $changes
    | ([$changes[] | select((.createdAt | fromdateiso8601) <= $at)] | last | .currentRefName)
      // $changes[0].previousRefName
      // .baseRefName'
}

keep_base_ref() {
  echo "::warning::$1 The base branch is '$base_ref'." >&2
  echo "$base_ref"
  exit 0
}

base_ref=${1:?Usage: $0 <github.base_ref> <PR number>}
pr=${2:?Usage: $0 <github.base_ref> <PR number>}
repository=${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}

built_at=$(git show -s --format=%ct HEAD) ||
  keep_base_ref "Cannot read the commit time of HEAD."
reply=$(gh api graphql -f owner="${repository%/*}" -f name="${repository#*/}" -F pr="$pr" -f query="$BASE_CHANGES_QUERY") ||
  keep_base_ref "Cannot read the base changes of PR #$pr from the GitHub API."
base=$(base_at "$built_at" <<<"$reply") ||
  keep_base_ref "The GitHub API reply names no base branch for PR #$pr."

[ "$base" = "$base_ref" ] || echo ">>> The event names '$base_ref', but HEAD is built on '$base'." >&2
echo "$base"
