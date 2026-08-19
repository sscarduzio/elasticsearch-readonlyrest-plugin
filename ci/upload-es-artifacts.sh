#!/bin/bash
#
# Mirrors the ES jars (and their generated POMs) of one or more ES versions into the ROR libs store, for
# versions Elastic has released but not yet published to Maven Central. The es*x modules resolve those
# versions from the store until Central catches up — see the repository declared in
# readonlyrest.plugin-common-conventions, and LibsStore for where both sides get the address.
#
# This is a MANUAL step of supporting a new ES version, not something CI decides on its own: run it once
# per new ES release, then never again for that version.
#
#   Which versions:  ES_VERSIONS_TO_UPLOAD, space- or comma-separated (e.g. "9.5.1 9.4.5 8.19.20").
#   How to run it:   the "Mirror ES Libs" workflow (.github/workflows/mirror-es-libs.yml) — manual only.
#   Locally:         ES_VERSIONS_TO_UPLOAD="9.5.1" ci/upload-es-artifacts.sh
#                    (needs ROR_S3_ACCESS_KEY_ID / _SECRET in the environment)
#
# Versions used to be selected by uncommenting a line here, pushing a `newes/*` branch to fire ci.yml's
# es_s3_up job, and re-commenting before merge. That made "nothing was uploaded" and "the upload was not
# asked for" indistinguishable: a push that forgot the uncomment produced a GREEN job that transferred
# nothing, and the mistake only surfaced later, and in disguise, as an es*x module failing to resolve
# org.elasticsearch:elasticsearch:X.Y.Z. Uploaded versions are now recorded in git history (the workflow
# run) rather than in commented-out code.
#
# An empty list is not an error HERE — see the warning below — because this script is also runnable by
# hand. The workflow, whose whole reason to exist is mirroring something, rejects it before calling us.

set -eo pipefail

echo ">>> ($0) UPLOADING ES ARTIFACTS ..."

VERSIONS_INPUT="${ES_VERSIONS_TO_UPLOAD:-}"

# Not an error here: this script is runnable by hand, and "show me what it would do" should not fail.
# It is announced loudly all the same — a silent no-op is exactly what used to be mistaken for a
# successful upload. The workflow turns the same state into an error before it ever gets here.
if [ -z "$(echo "$VERSIONS_INPUT" | tr -d '[:space:],')" ]; then
  echo "::warning::ES_VERSIONS_TO_UPLOAD is empty — NO ES artifacts were uploaded to the libs store."
  echo "    Set it (e.g. ES_VERSIONS_TO_UPLOAD='9.5.1 9.4.5') to mirror an ES version's jars."
  exit 0
fi

read -r -a VERSIONS <<< "$(echo "$VERSIONS_INPUT" | tr ',' ' ')"

# Validate the whole list before uploading anything: a typo must not leave the store holding a half-done
# mirror under a junk version directory that someone later has to find and delete by hand.
for VERSION in "${VERSIONS[@]}"; do
  if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9]+)?$ ]]; then
    echo "::error::'$VERSION' is not an ES version (expected X.Y.Z)"
    exit 1
  fi
done

echo ">>> Uploading ES artifacts for: ${VERSIONS[*]}"

for VERSION in "${VERSIONS[@]}"; do
  echo ""
  echo ">>> ES $VERSION"
  # `clean` between versions: the mirror tasks stage the jars they publish in ror-tools/build, and a
  # previous version's staged jars there would be published again under this version's coordinates.
  ./gradlew --stacktrace clean ror-tools:uploadArtifactsFromEsBinaries "-PesVersion=$VERSION" </dev/null
done

echo ""
echo ">>> DONE — uploaded ES artifacts for: ${VERSIONS[*]}"
