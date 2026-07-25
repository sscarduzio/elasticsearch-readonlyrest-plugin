#!/bin/bash

# Cancels stale Azure Pipelines runs for the same PR ("newest run for a PR wins").
#
# Azure's `pr.autoCancel: true` only evaluates supersession once, when a run is QUEUED. Two PR
# pushes seconds apart can both start before that check fires, leaving duplicate runs burning
# agents (observed: builds 10409 + 10410 on the same PR, 2026-06-15). This script runs FIRST in
# every PR pipeline and enforces the rule by talking to the Azure Builds REST API:
#   * cancel every still-active run for the SAME PR with a LOWER build id (older → kill it), and
#   * if a run with a HIGHER build id already exists (a newer push beat us), self-cancel.
#
# Non-PR runs (branch/master/develop/schedule/manual) are left untouched — they rely on
# `trigger.batch` and must not cancel each other. Transient API errors are non-fatal (logged, then
# the run proceeds ungated): a flaky API must not block a legitimate build. Two cases DO exit
# non-zero on purpose: a missing API token (a configuration error — fail fast and loud), and the
# self-cancel path (a newer run exists — stop this superseded run from doing real work).

set -uo pipefail

log() { echo ">>> $*"; }
die() { echo ">>> ERROR: $*" >&2; exit 1; }

# --- environment -------------------------------------------------------------------------------

# Only PR runs participate; everything else returns immediately.
if [[ "${BUILD_REASON:-}" != "PullRequest" ]]; then
  log "reason='${BUILD_REASON:-}' is not PullRequest — nothing to cancel."
  exit 0
fi

# A missing token is a configuration error (the env: mapping in azure-pipelines.yml is wrong or the
# job-authorization scope stripped it), not a transient runtime condition — fail fast so it's fixed
# rather than silently leaving every PR ungated.
: "${AZURE_BUILDS_API_TOKEN:?not set — map \$(System.AccessToken) to AZURE_BUILDS_API_TOKEN in azure-pipelines.yml}"
ORG_URL="${SYSTEM_COLLECTIONURI:?}"          # e.g. https://dev.azure.com/beshu-tech/
PROJECT="${SYSTEM_TEAMPROJECTID:?}"          # project GUID (stable, space-safe)
DEFINITION_ID="${SYSTEM_DEFINITIONID:?}"     # this pipeline's definition id
SELF_ID="${BUILD_BUILDID:?}"                 # this run's build id
PR_REF="${BUILD_SOURCEBRANCH:?}"             # refs/pull/<n>/merge — uniquely identifies the PR

readonly API_VERSION="api-version=7.1"
readonly BUILDS_API="${ORG_URL%/}/${PROJECT}/_apis/build/builds"
# --fail-with-body: a 401/403 becomes a non-zero exit + visible body instead of an error JSON that
#   would parse to "no other runs" (a silent no-op). --max-time/--connect-timeout: a hung API can
#   never hold the run. Shared by every call so the hardening can't be forgotten at one call site.
readonly CURL_OPTS=(-sS --fail-with-body --connect-timeout 10 --max-time 20
                    -H "Authorization: Bearer ${AZURE_BUILDS_API_TOKEN}")

# --- Azure Builds REST API (technical details live here; main flow below reads cleanly) ---------

# Echoes the build ids of OTHER active (queued/running) runs for this PR, one per line, ascending.
# Returns non-zero if the API call fails or the body can't be parsed (caller decides what to do).
list_other_active_run_ids() {
  local resp ids_file jq_err
  # branchName filters server-side to this PR's runs; -G + --data-urlencode encodes the '/' in
  # refs/pull/N/merge and the ',' in statusFilter (Azure path/query handling is stricter than raw).
  resp="$(curl "${CURL_OPTS[@]}" -G "${BUILDS_API}" \
    --data-urlencode "definitions=${DEFINITION_ID}" \
    --data-urlencode "branchName=${PR_REF}" \
    --data-urlencode "statusFilter=inProgress,notStarted" \
    --data-urlencode "${API_VERSION}")" || return 1

  # A private temp file (not a fixed /tmp path) — two cancelers can run concurrently on a shared
  # self-hosted agent and would clobber each other. Surface (don't swallow) jq parse errors so a
  # non-JSON body isn't mistaken for "no other runs".
  ids_file="$(mktemp)"
  trap 'rm -f "${ids_file}"' RETURN
  jq_err="$(echo "${resp}" | jq -r --argjson self "${SELF_ID}" \
    '.value[]? | select(.id != $self) | .id' 2>&1 1>"${ids_file}")"
  if [[ -n "${jq_err}" ]]; then
    log "WARN: could not parse Builds API response (${jq_err})"
    log "response body: ${resp}"
    return 1
  fi
  sort -n "${ids_file}"
}

# Requests cancellation of a build. <superseder-id> is only for the log line.
cancel_run() { # cancel_run <id> <superseder-id>
  local id="$1" superseder="$2"
  log "cancelling run ${id} (superseded by ${superseder})"
  # `?${API_VERSION}` is interpolated raw (the GET uses --data-urlencode): API_VERSION is the static
  # "api-version=7.1" and ${id} is numeric — no characters that need encoding, no injection surface.
  curl "${CURL_OPTS[@]}" -X PATCH -H "Content-Type: application/json" \
    -d '{"status":"cancelling"}' "${BUILDS_API}/${id}?${API_VERSION}" >/dev/null \
    || log "WARN: cancel of ${id} failed (HTTP error/timeout) — continuing"
}

# --- main flow ---------------------------------------------------------------------------------

log "self=run ${SELF_ID} pr_ref=${PR_REF} definition=${DEFINITION_ID}"

if ! mapfile -t other_ids < <(list_other_active_run_ids); then
  log "WARN: cannot determine other runs — proceeding without cancelling."
  exit 0
fi

if [[ "${#other_ids[@]}" -eq 0 ]]; then
  log "no other active runs for this PR — proceeding."
  exit 0
fi
log "other active runs for this PR: ${other_ids[*]}"

# Newest run wins: cancel every older run; if a newer one already exists, self-cancel.
newest_other=0
for id in "${other_ids[@]}"; do
  if [[ "${id}" -lt "${SELF_ID}" ]]; then
    cancel_run "${id}" "${SELF_ID}"
  elif [[ "${id}" -gt "${SELF_ID}" && "${id}" -gt "${newest_other}" ]]; then
    newest_other="${id}"
  fi
done

if [[ "${newest_other}" -gt 0 ]]; then
  log "a newer run (${newest_other}) for this PR exists — self-cancelling run ${SELF_ID}."
  cancel_run "${SELF_ID}" "${newest_other}"
  # Non-zero so the pipeline stops here instead of doing real work while the cancellation lands.
  exit 1
fi

log "this is the newest run for the PR — proceeding."
exit 0
