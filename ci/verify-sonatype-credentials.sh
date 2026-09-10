#!/usr/bin/env bash

# Asks the Sonatype staging API for our profiles. A 200 means it accepts the credentials.
#
# It also checks that MAVEN_STAGING_PROFILE_ID is a profile the account owns, because the publish
# task sends that id straight to the call that creates the staging repository. A stale id fails
# there, and the failure looks the same as a bad password.
#
# Nothing here prints a credential or the response body.

set -euo pipefail

API="https://ossrh-staging-api.central.sonatype.com/service/local"

fail() {
  echo ">>> ERROR: $1"
  echo ">>> Fix it before the next release. The publish job cannot recover from this by retrying:"
  echo ">>>   a 401 here is a rejected identity, not a busy server."
  exit 1
}

[ -n "${MAVEN_REPO_USER:-}" ] || fail "MAVEN_REPO_USER is empty."
[ -n "${MAVEN_REPO_PASSWORD:-}" ] || fail "MAVEN_REPO_PASSWORD is empty."

body="$(mktemp)"
trap 'rm -f "$body"' EXIT

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
