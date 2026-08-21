#!/bin/bash
set -euo pipefail

# Fetches the ES action-string list for every supported ES version and commits the new ones to the
# readonlyrest-docs checkout (see .github/workflows/actionstrings_gen.yml).
#
# The version list comes from the BUILD (each es*x module's `supportedEsVersions`), not from parsing
# gradle.properties here: a second parser drifts. It did — this script used to read the versions with
# `awk -F= '{print $2}'` over the whole properties file, which stopped working when the single
# `esVersion=X.Y.Z` line became a multi-line `supportedEsVersions` list. It then extracted a backslash
# and committed an empty `action_strings_es\.txt` to the docs repo.

cd "$(dirname "$0")/../.."

ES_VERSIONS=$(./gradlew --quiet printAllSupportedEsVersions)

if [ -z "$ES_VERSIONS" ]; then
  echo "::error::no supported ES versions returned by printAllSupportedEsVersions"
  exit 1
fi

failed=()
for VERSION in $ES_VERSIONS; do
  # Guard the invariant the old parser broke silently: anything that is not an ES version must never
  # reach fetchIfNecessary.sh, or it becomes a junk file in the docs repo.
  if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "::error::'$VERSION' is not an ES version"
    exit 1
  fi
  echo "check actions for $VERSION if needed"
  if ! ci/actionstrings/fetchIfNecessary.sh "$VERSION"; then
    # Keep going: one unavailable ES tag must not block the remaining versions. The job still fails.
    echo "::warning::action strings for ES $VERSION could not be generated"
    failed+=("$VERSION")
  fi
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "::error::failed for: ${failed[*]}"
  exit 1
fi

echo "done checking action strings."
