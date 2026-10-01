#!/bin/bash
# Name each failed test of the JUnit XML reports under a directory. Without it, a failed shard of an
# integration-test leg says only "per-shard exit codes: [0, 0, 0, 1]", and the test name sits in an
# artifact.
#
# Prints one `class<TAB>test<TAB>message` line per failed or errored test case. Under GitHub Actions
# it also writes an error annotation for each of the first MAX_ANNOTATIONS, because GitHub shows no
# more than 10 of one kind per step, and it lists all of them in the job summary.
#
# The parser reads the layout Gradle writes: each <testcase> opens on its own line, and its
# <failure> or <error> element follows on a new line.
#
# Usage: ci/junit-failures.sh <directory> <title>
#   directory  searched recursively for the TEST-*.xml files under a test-results directory
#   title      names the job in the annotations and the summary. Example: it_linux_es80x
set -euo pipefail

DIR=${1:?directory required}
TITLE=${2:?title required}
MAX_ANNOTATIONS=10
MAX_SUMMARY_ROWS=100

failures() {
  find "$DIR" -path '*/test-results/*' -name 'TEST-*.xml' -type f -print0 | xargs -0 -r awk '
    function attr(line, key,   v) {
      # The leading space keeps "name" from matching the end of "classname".
      if (!match(line, " " key "=\"[^\"]*\"")) return ""
      v = substr(line, RSTART + length(key) + 3, RLENGTH - length(key) - 4)
      gsub(/&quot;/, "\"", v); gsub(/&apos;/, "\x27", v); gsub(/&lt;/, "<", v); gsub(/&gt;/, ">", v)
      gsub(/&#10;|&#13;/, " ", v); gsub(/&amp;/, "\\&", v)
      return v
    }
    # Test output is CDATA, so it can hold a raw "<error " that is not an element.
    /<system-(out|err)>/ { in_output = 1 }
    /<\/system-(out|err)>/ { in_output = 0; next }
    in_output { next }
    /<testcase / { class = attr($0, "classname"); test = attr($0, "name") }
    /<(failure|error)[ >]/ {
      message = substr(attr($0, "message"), 1, 200)
      printf "%s\t%s\t%s\n", class, test, message
    }'
}

# A table cell is markdown, where "<x>" reads as an HTML tag and "|" ends the cell.
cell() {
  # Quoted: bash 5.2 reads a bare & in the replacement as the matched text.
  local s=${1//&/'&amp;'}
  s=${s//</'&lt;'}
  echo "${s//|/\\|}"
}

# A workflow command ends at the line end, and % starts an escape.
escape() {
  local s=${1//%/%25}
  echo "${s//$'\r'/%0D}"
}

# A property value, such as the title, also escapes ":" and ",".
escape_property() {
  local s
  s=$(escape "$1")
  s=${s//:/%3A}
  echo "${s//,/%2C}"
}

main() {
  [ -d "$DIR" ] || return 0
  local lines
  lines=$(failures)
  [ -n "$lines" ] || return 0

  echo "$lines"
  [ -n "${GITHUB_ACTIONS:-}" ] || return 0

  local class test message total count=0
  total=$(wc -l <<< "$lines")
  while IFS=$'\t' read -r class test message; do
    count=$((count + 1))
    [ "$count" -le "$MAX_ANNOTATIONS" ] || break
    echo "::error title=$(escape_property "$TITLE: ${class##*.}")::$(escape "$test: $message")"
  done <<< "$lines"

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Failed tests: $TITLE"
      echo
      echo '| Class | Test | Message |'
      echo '|---|---|---|'
      # GitHub drops a summary above 1 MiB, so the table stops at MAX_SUMMARY_ROWS.
      head -n "$MAX_SUMMARY_ROWS" <<< "$lines" | while IFS=$'\t' read -r class test message; do
        echo "| $(cell "$class") | $(cell "$test") | $(cell "$message") |"
      done
      if [ "$total" -gt "$MAX_SUMMARY_ROWS" ]; then
        echo
        echo "$((total - MAX_SUMMARY_ROWS)) more. The \`*-results\` artifact holds all the reports."
      fi
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

main
