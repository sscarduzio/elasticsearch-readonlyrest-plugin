#!/bin/bash
# Prints the ES action strings of one ES version tag, one per line, to standard output.
#
# Standard output carries the list and nothing else: a caller redirects it into the file it
# publishes. Every message about the run goes to standard error.

# shellcheck source=ci/log.sh
source "$(dirname "${BASH_SOURCE[0]}")/../log.sh"

if [ $# -ne 1 ]
  then
    ci_log "Usage: $0 <ES version tag>, for example: $0 v7.9.0"
    exit 1
fi

ES_VERSION=$1
TEMPDIR="$(mktemp -d -t "ror-ci-es-actions-${ES_VERSION}-XXXXXXXXXX")"

git clone --quiet --branch "$ES_VERSION" --depth 1 "https://github.com/elastic/elasticsearch" "$TEMPDIR"
# Match action strings in both old (String NAME =) and new (ActionType<...> TYPE =) declaration styles.
# ES 8.13+ moved some action definitions from standalone Action classes to Transport*Action classes,
# changing the field type from String to ActionType<>. Both patterns declare the action string inline.
grep -E -ri --include="*.java" 'public.*static.*final.*(String|ActionType).*"[a-z]+:[a-z]' "$TEMPDIR" \
  | grep -v mock \
  | sed 's/.*"\([^"]*\)".*/"\1"/' \
  | grep '^"[a-z]*:[a-z].*/[a-z]' \
  | sort -u
rm -rf "$TEMPDIR"
