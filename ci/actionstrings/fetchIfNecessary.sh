#!/bin/bash
set -euo pipefail

# Generates the action-string list for ONE ES version and commits it to the readonlyrest-docs checkout,
# unless that version's file already exists. Called by run.sh.

ES_VERSION=$1

DEST_DIR="readonlyrest-docs/actionstrings"
mkdir -p "$DEST_DIR"

FILENAME="$DEST_DIR/action_strings_es$ES_VERSION.txt"
if test -f "$FILENAME"; then
  echo "$FILENAME exists."
  exit 0
fi

echo "processing ES action extractions for: $FILENAME"

# Write to a temporary file first and only publish a non-empty result. Writing straight into $FILENAME
# published whatever the extraction produced, so a failed clone (a tag that does not exist, a network
# error) committed an EMPTY list, and the missing-file check above then treated that version as done
# forever.
TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT

if ! ci/actionstrings/fetch.sh "v$ES_VERSION" > "$TMP_FILE"; then
  echo "extraction failed for ES $ES_VERSION"
  exit 1
fi
if [ ! -s "$TMP_FILE" ]; then
  echo "no action strings found for ES $ES_VERSION (is the tag v$ES_VERSION published?)"
  exit 1
fi

mv "$TMP_FILE" "$FILENAME"
trap - EXIT
cat "$FILENAME"

# No git commands here on purpose. This script runs with the ROR repo as the working directory, and
# $FILENAME lives inside the NESTED readonlyrest-docs checkout, so `git add` here stages nothing (git
# ignores paths inside an embedded repository) and `git commit <that path>` fails with "did not match
# any file(s) known to git". The workflow publishes everything with a single commit in the docs repo
# after run.sh finishes.
