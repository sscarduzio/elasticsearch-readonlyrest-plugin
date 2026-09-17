# shellcheck shell=bash
# Sourced by .github/workflows/verify-publish-credentials.yml — do not execute directly.
#
# The two halves of the daily credential check. They belong together: the second one only says
# where the first one failed.
#
#   verify_credentials         — ask the staging API whether it still accepts our identity
#   report_credentials_failure — put a failed check where someone reads it

API="https://ossrh-staging-api.central.sonatype.com/service/local"

fail() {
  echo ">>> ERROR: $1"
  echo ">>> Fix it before the next release. The publish job cannot recover from this by retrying:"
  echo ">>>   a 401 here is a rejected identity, not a busy server."
  exit 1
}

# Asks the Sonatype staging API for our profiles. A 200 means it accepts the credentials.
#
# It also checks that MAVEN_STAGING_PROFILE_ID is a profile the account owns, because the publish
# task sends that id straight to the call that creates the staging repository. A stale id fails
# there, and the failure looks the same as a bad password.
#
# Nothing here prints a credential or the response body.
verify_credentials() {
  [ -n "${MAVEN_REPO_USER:-}" ] || fail "MAVEN_REPO_USER is empty."
  [ -n "${MAVEN_REPO_PASSWORD:-}" ] || fail "MAVEN_REPO_PASSWORD is empty."

  local body status
  body="$(mktemp)"
  # shellcheck disable=SC2064 # expand now: $body is local and gone when the trap runs
  trap "rm -f '$body'" EXIT

  status="$(curl -sS -o "$body" -w '%{http_code}' \
    -H 'Accept: application/json' \
    -u "$MAVEN_REPO_USER:$MAVEN_REPO_PASSWORD" \
    "$API/staging/profiles")" || fail "Cannot reach $API/staging/profiles."

  case "$status" in
    200) echo ">>> Sonatype credentials accepted." ;;
    401|403)
      fail "The staging API rejected the credentials with HTTP $status.
>>>   The API wants a Central Portal user token (central.sonatype.com -> Account -> Generate
>>>   User Token), not the legacy OSSRH login. Set MAVEN_REPO_USER and MAVEN_REPO_PASSWORD in
>>>   the Doppler project ror_ci (config prd). A direct edit in GitHub is overwritten by the sync."
      ;;
    *) fail "Unexpected HTTP $status from $API/staging/profiles." ;;
  esac

  if [ -n "${MAVEN_STAGING_PROFILE_ID:-}" ]; then
    if grep -q "$MAVEN_STAGING_PROFILE_ID" "$body"; then
      echo ">>> MAVEN_STAGING_PROFILE_ID belongs to this account."
    else
      fail "MAVEN_STAGING_PROFILE_ID is not a profile this account owns.
>>>   build.gradle sends it to the create-staging-repository call, which then fails.
>>>   Take the id from the staging API instead of the old OSSRH console, and set it in Doppler."
    fi
  else
    echo ">>> MAVEN_STAGING_PROFILE_ID is empty; the publish plugin will look the profile up."
  fi
}

# Makes a failed credential check visible without a new secret.
#
# One issue, reused. A daily job that opens a new issue every morning trains everyone to ignore it,
# so this comments on the open one instead and opens a fresh issue only when none is open.
report_credentials_failure() {
  local title existing body
  title="Publish credentials are not working"
  # No label: the repo has no CI label, and a failure handler must not fail on a missing one.

  existing="$(gh issue list --state open --search "\"$title\" in:title" --json number,title \
    --jq "[.[] | select(.title == \"$title\")] | .[0].number // empty")"

  body="The scheduled credential check failed.

Run: ${RUN_URL:-unknown}

\`publish_mvn\` cannot publish while this is red, and it fails quietly: when the version in
\`gradle.properties\` is already published the job skips and reports success, so a release is the
first thing that finds out. The job log says which credential the staging API rejected and where to
set it."

  if [ -n "$existing" ]; then
    echo ">>> Issue #$existing is already open; adding a comment."
    gh issue comment "$existing" --body "$body"
  else
    echo ">>> Opening an issue."
    gh issue create --title "$title" --body "$body"
  fi
}
