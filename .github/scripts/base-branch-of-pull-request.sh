#!/usr/bin/env bash
# Prints the base that the PR had when GitHub built HEAD. github.base_ref can name an older base, and
# the current base of the PR can be newer than HEAD. When a step fails, prints base_ref and a warning.
# Risk: GitHub does not document that the commit time of HEAD is the build time. A clock difference
# can give the old base to a push some seconds after a change of base.

set -uo pipefail

# shellcheck disable=SC2016 # GraphQL variables, not shell ones
BASE_CHANGES_QUERY='query($owner: String!, $name: String!, $pr: Int!) {
  repository(owner: $owner, name: $name) { pullRequest(number: $pr) { baseRefName
    timelineItems(itemTypes: [BASE_REF_CHANGED_EVENT], last: 100) {
      nodes { ... on BaseRefChangedEvent { createdAt previousRefName currentRefName } } } } } }'

# Reads the reply to BASE_CHANGES_QUERY on stdin. Prints the base of the PR at $1, in epoch seconds.
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
