#!/bin/bash

# Deterministic supersede guard for PR runs (closes Azure's at-queue-time autoCancel race).
#
# Azure's `pr.autoCancel: true` only evaluates supersession once, when a run is QUEUED. Two PR
# pushes seconds apart can both start before that check fires, leaving duplicate runs burning
# agents (observed: builds 10409 + 10410 on the same PR, 2026-06-15). This script runs FIRST in
# every PR pipeline and enforces "newest run for a PR wins" by talking to the Builds REST API:
#   * cancel every still-active run for the SAME PR ref with a LOWER build id (older → kill it), and
#   * if a run with a HIGHER build id already exists (a newer push beat us), self-cancel.
#
# Non-PR runs (branch/master/develop/schedule/manual) are left untouched — they rely on
# `trigger.batch` and must not cancel each other. Failures here are non-fatal: a broken guard must
# never block a legitimate build, so the script always exits 0.

set -uo pipefail

echo ">>> ($0) SUPERSEDE GUARD ..."

# Only PR runs participate. Everything else returns immediately.
if [[ "${BUILD_REASON:-}" != "PullRequest" ]]; then
  echo ">>> reason='${BUILD_REASON:-}' is not PullRequest — supersede guard skipped."
  exit 0
fi

: "${SYSTEM_ACCESSTOKEN:?SYSTEM_ACCESSTOKEN not set — map it via env (see azure-pipelines.yml)}"
ORG_URL="${SYSTEM_COLLECTIONURI:?}"          # e.g. https://dev.azure.com/beshu-tech/
PROJECT="${SYSTEM_TEAMPROJECTID:?}"          # project GUID (stable, space-safe)
DEFINITION_ID="${SYSTEM_DEFINITIONID:?}"     # this pipeline's definition id
SELF_ID="${BUILD_BUILDID:?}"                 # this run's build id
PR_REF="${BUILD_SOURCEBRANCH:?}"             # refs/pull/<n>/merge — uniquely identifies the PR
API="api-version=7.1"

base="${ORG_URL%/}/${PROJECT}/_apis/build/builds"
auth=(-H "Authorization: Bearer ${SYSTEM_ACCESSTOKEN}")

echo ">>> self=build ${SELF_ID} pr_ref=${PR_REF} definition=${DEFINITION_ID}"

# Active runs = notStarted + inProgress, for this definition, on this exact PR ref.
# branchName filters server-side so we only ever see the same PR's runs.
list_url="${base}?definitions=${DEFINITION_ID}&branchName=${PR_REF}&statusFilter=inProgress,notStarted&${API}"
resp="$(curl -sS "${auth[@]}" "${list_url}")" || { echo ">>> WARN: list call failed; not gating."; exit 0; }

# Build ids of OTHER active runs on the same PR ref.
mapfile -t other_ids < <(echo "${resp}" | jq -r --argjson self "${SELF_ID}" \
  '.value[]? | select(.id != $self) | .id' 2>/dev/null | sort -n)

if [[ "${#other_ids[@]}" -eq 0 ]]; then
  echo ">>> no other active runs for this PR — proceeding."
  exit 0
fi
echo ">>> other active runs on this PR: ${other_ids[*]}"

cancel_build() { # cancel_build <id>
  local id="$1"
  echo ">>> cancelling build ${id} (superseded by ${SELF_ID})"
  curl -sS -X PATCH "${auth[@]}" -H "Content-Type: application/json" \
    -d '{"status":"cancelling"}' "${base}/${id}?${API}" >/dev/null \
    || echo ">>> WARN: cancel of ${id} failed (continuing)"
}

newer_exists=0
for id in "${other_ids[@]}"; do
  if [[ "${id}" -lt "${SELF_ID}" ]]; then
    cancel_build "${id}"            # older run → kill it; this run is newer
  elif [[ "${id}" -gt "${SELF_ID}" ]]; then
    newer_exists=1                  # a newer run already exists → we are superseded
  fi
done

if [[ "${newer_exists}" -eq 1 ]]; then
  echo ">>> a newer run for this PR already exists — self-cancelling build ${SELF_ID}."
  cancel_build "${SELF_ID}"
  # Fail this job so the pipeline stops here instead of doing real work while cancellation lands.
  exit 1
fi

echo ">>> this is the newest run for the PR — proceeding."
exit 0
