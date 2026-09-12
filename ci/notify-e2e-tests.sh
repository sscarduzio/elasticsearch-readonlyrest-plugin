#!/usr/bin/env bash
# Tells the e2e tests repo that a release published new ROR ES plugin images.
#
# The bootstrap sweep there boots every released ELK version against the `<elk>-ror-latest` tags.
# Those tags move only when a release pushes them, about twice a month, so the sweep waits for this
# event instead of a nightly cron.
#
# The event name and the repo below are the whole contract. The receiver reads the payload only to
# show where the event came from, so a new key here breaks nothing there.
#
# No `set -e`, and no command here may end the script on a failure: see notify_skipped() below.
set -uo pipefail

# shellcheck source=ci/ci-lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/ci-lib.sh"

E2E_TESTS_REPO="beshu-tech/readonlyrest-e2e-tests"
EVENT_TYPE="ror-plugins-released"

# The release is already published when this runs: the zips are on S3, the images are on Docker Hub
# and the git tags are pushed. A failed notification takes none of that back, so it must never turn
# the run red — it would say the release failed, and someone would try to repeat it. Every path out
# of this script therefore ends here or at the success message, and both exit 0.
notify_skipped() {
  echo "WARN: the e2e tests repo was not notified: $1"
  echo "      The release itself is unaffected. Start the sweep by hand with:"
  echo "      gh workflow run all-e2e-tests.yml -R $E2E_TESTS_REPO"
  exit 0
}

[ -n "${ROR_GH_TOKEN:-}" ] || notify_skipped "ROR_GH_TOKEN is empty"

ROR_VERSION=$(gradle_property pluginVersion) || notify_skipped "gradle.properties has no pluginVersion"

RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"

PAYLOAD=$(jq -n \
  --arg event_type "$EVENT_TYPE" \
  --arg plugin "elasticsearch" \
  --arg version "$ROR_VERSION" \
  --arg repository "${GITHUB_REPOSITORY:-}" \
  --arg ref "${GITHUB_REF:-}" \
  --arg sha "${GITHUB_SHA:-}" \
  --arg run_url "$RUN_URL" \
  '{event_type: $event_type, client_payload: {plugin: $plugin, version: $version, repository: $repository, ref: $ref, sha: $sha, run_url: $run_url}}'
) || notify_skipped "could not build the request body"

echo ">>> Notifying $E2E_TESTS_REPO: $EVENT_TYPE (ROR $ROR_VERSION)"

BODY_FILE=$(mktemp) || notify_skipped "could not create a temp file for the response"
trap 'rm -f "$BODY_FILE"' EXIT

# --retry covers a connection that never answers. GitHub answers 204 and nothing else on success.
HTTP_CODE=$(curl --silent --show-error \
  --retry 3 --retry-delay 5 --max-time 60 \
  --output "$BODY_FILE" --write-out '%{http_code}' \
  --request POST \
  --header "Accept: application/vnd.github+json" \
  --header "Authorization: Bearer $ROR_GH_TOKEN" \
  --header "X-GitHub-Api-Version: 2022-11-28" \
  --data "$PAYLOAD" \
  "https://api.github.com/repos/$E2E_TESTS_REPO/dispatches")

if [ "$HTTP_CODE" != "204" ]; then
  # 404 is what GitHub answers for a repo the token cannot write, so name that case: the API hides
  # a permission problem behind "not found".
  notify_skipped "POST /repos/$E2E_TESTS_REPO/dispatches answered $HTTP_CODE ($(tr -s '[:space:]' ' ' < "$BODY_FILE" | head -c 300)).
      A 404 means ROR_GH_TOKEN cannot write to that repo, not that the repo is absent."
fi

echo ">>> Notified $E2E_TESTS_REPO"
