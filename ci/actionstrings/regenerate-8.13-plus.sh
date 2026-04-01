#!/bin/bash
#
# One-off script to regenerate action string files for ES 8.13+ versions.
# These were previously extracted with a regex that only matched String declarations,
# missing ActionType<> declarations introduced in ES 8.13.
#
# Usage:
#   1. Clone beshu-tech/readonlyrest-docs into ./readonlyrest-docs
#   2. Run this script from the repo root: bash ci/actionstrings/regenerate-8.13-plus.sh
#   3. cd readonlyrest-docs && git add . && git commit && git push
#
# This script can be deleted after the one-time backfill is complete.

set -euo pipefail

DEST_DIR="readonlyrest-docs/actionstrings"

if [ ! -d "$DEST_DIR" ]; then
  echo "ERROR: $DEST_DIR not found. Clone beshu-tech/readonlyrest-docs into ./readonlyrest-docs first."
  exit 1
fi

# All ES versions >= 8.13 that have existing (incomplete) action string files
VERSIONS=$(ls "$DEST_DIR"/action_strings_es*.txt 2>/dev/null \
  | sed 's|.*/action_strings_es||; s|\.txt$||' \
  | while read -r v; do
      major=$(echo "$v" | cut -d. -f1)
      minor=$(echo "$v" | cut -d. -f2 | sed 's/[^0-9].*//')
      if [ "$major" -gt 8 ] || { [ "$major" -eq 8 ] && [ "$minor" -ge 13 ]; }; then
        echo "$v"
      fi
    done)

TOTAL=$(echo "$VERSIONS" | wc -l | tr -d ' ')
COUNT=0

# Single full clone (no --depth 1, we need all tags), then checkout per version
ES_REPO=$(mktemp -d -t ror-regen-es-XXXXXXXXXX)
echo "Cloning elasticsearch repo (full tags)... this takes a minute."
git clone --quiet --no-checkout --filter=blob:none \
  "https://github.com/elastic/elasticsearch" "$ES_REPO"
echo "Clone done."

for VERSION in $VERSIONS; do
  COUNT=$((COUNT + 1))
  FILENAME="$DEST_DIR/action_strings_es${VERSION}.txt"
  OLD_COUNT=$(wc -l < "$FILENAME" | tr -d ' ')

  echo "[$COUNT/$TOTAL] Regenerating $VERSION (was $OLD_COUNT strings)..."

  # Checkout the tag — blobs are fetched on demand thanks to --filter=blob:none
  git -C "$ES_REPO" checkout --quiet "v$VERSION" 2>/dev/null || {
    echo "  SKIP: tag v$VERSION not found"
    continue
  }

  egrep -ri --include="*.java" \
    'public.*static.*final.*(String|ActionType).*"[a-z]+:[a-z]' "$ES_REPO" \
    | grep -v mock \
    | sed 's/.*"\([^"]*\)".*/"\1"/' \
    | grep '^"[a-z]*:[a-z].*/[a-z]' \
    | sort -u > "$FILENAME"

  NEW_COUNT=$(wc -l < "$FILENAME" | tr -d ' ')
  DIFF=$((NEW_COUNT - OLD_COUNT))

  if [ "$DIFF" -gt 0 ]; then
    echo "  -> $NEW_COUNT strings (+$DIFF new)"
  elif [ "$DIFF" -eq 0 ]; then
    echo "  -> $NEW_COUNT strings (unchanged)"
  else
    echo "  -> $NEW_COUNT strings ($DIFF)"
  fi
done

rm -rf "$ES_REPO"

echo ""
echo "Done. Review changes in $DEST_DIR, then commit and push."
