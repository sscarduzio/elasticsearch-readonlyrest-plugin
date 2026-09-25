#!/usr/bin/env bash
# The logger every CI script shares. Functions only; safe to source from any script.

# Writes one message about the run to standard error. A function that prints a value keeps standard
# output for that value, so a caller can capture the value alone.
ci_log() {
  echo "[CI] $*" >&2
}
