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
# `trigger.batch` and must not cancel each other. API errors are non-fatal (logged, then the run
# proceeds): a broken guard must never block a legitimate build. The ONE deliberate non-zero exit
# is the self-cancel path (a newer run already exists) — there we exit 1 to stop this superseded
# run from doing real work while the cancellation lands.

set -uo pipefail

echo ">>> ($0) SUPERSEDE GUARD ..."

# Only PR runs participate. Everything else returns immediately.
if [[ "${BUILD_REASON:-}" != "PullRequest" ]]; then
  echo ">>> reason='${BUILD_REASON:-}' is not PullRequest — supersede guard skipped."
  exit 0
fi

# A missing token must NOT block the build (same contract as every other failure path below):
# warn and proceed ungated rather than `:?`-exiting non-zero and failing the whole pipeline.
if [[ -z "${SYSTEM_ACCESSTOKEN:-}" ]]; then
  echo ">>> WARN: SYSTEM_ACCESSTOKEN not set (map it via env — see azure-pipelines.yml) — cannot query runs; proceeding without gating."
  exit 0
fi
ORG_URL="${SYSTEM_COLLECTIONURI:?}"          # e.g. https://dev.azure.com/beshu-tech/
PROJECT="${SYSTEM_TEAMPROJECTID:?}"          # project GUID (stable, space-safe)
DEFINITION_ID="${SYSTEM_DEFINITIONID:?}"     # this pipeline's definition id
SELF_ID="${BUILD_BUILDID:?}"                 # this run's build id
PR_REF="${BUILD_SOURCEBRANCH:?}"             # refs/pull/<n>/merge — uniquely identifies the PR
API="api-version=7.1"

base="${ORG_URL%/}/${PROJECT}/_apis/build/builds"
# --fail-with-body: a 401/403 (under-permissioned token) becomes a non-zero exit + visible body
#   instead of silently returning an error JSON that jq parses to "no other runs" (silent no-op).
# --max-time/--connect-timeout: never let a hung Azure API hold the guard (and thus the pipeline).
curl_opts=(-sS --fail-with-body --connect-timeout 10 --max-time 20
           -H "Authorization: Bearer ${SYSTEM_ACCESSTOKEN}")

echo ">>> self=build ${SELF_ID} pr_ref=${PR_REF} definition=${DEFINITION_ID}"

# Active runs = notStarted + inProgress, for this definition, on this exact PR ref.
# branchName filters server-side so we only ever see the same PR's runs. Let curl URL-encode the
# query params (-G + --data-urlencode) — branchName carries unencoded '/' (refs/pull/N/merge) that
# a stricter gateway could reject; encoding it is strictly safer than embedding it raw.
if ! resp="$(curl "${curl_opts[@]}" -G "${base}" \
      --data-urlencode "definitions=${DEFINITION_ID}" \
      --data-urlencode "branchName=${PR_REF}" \
      --data-urlencode "statusFilter=inProgress,notStarted" \
      --data-urlencode "${API}")"; then
  echo ">>> WARN: list call failed (HTTP error/timeout) — cannot determine supersession; proceeding without gating."
  echo ">>> response body: ${resp}"
  exit 0
fi

# Build ids of OTHER active runs on the same PR ref. Surface (don't swallow) a jq parse failure:
# if the body isn't the JSON we expect, an empty other_ids would otherwise look like "no other runs"
# and the guard would silently no-op. On parse failure: warn and proceed ungated.
jq_err="$(echo "${resp}" | jq -r --argjson self "${SELF_ID}" \
  '.value[]? | select(.id != $self) | .id' 2>&1 1>/tmp/supersede_ids.txt)"
if [[ -n "${jq_err}" ]]; then
  echo ">>> WARN: could not parse Builds API response (${jq_err}) — proceeding without gating."
  echo ">>> response body: ${resp}"
  exit 0
fi
mapfile -t other_ids < <(sort -n /tmp/supersede_ids.txt)

if [[ "${#other_ids[@]}" -eq 0 ]]; then
  echo ">>> no other active runs for this PR — proceeding."
  exit 0
fi
echo ">>> other active runs on this PR: ${other_ids[*]}"

cancel_build() { # cancel_build <id> <superseder-id>
  local id="$1" superseder="$2"
  echo ">>> cancelling build ${id} (superseded by ${superseder})"
  curl "${curl_opts[@]}" -X PATCH -H "Content-Type: application/json" \
    -d '{"status":"cancelling"}' "${base}/${id}?${API}" >/dev/null \
    || echo ">>> WARN: cancel of ${id} failed (HTTP error/timeout) — continuing"
}

newest_other=0
for id in "${other_ids[@]}"; do
  if [[ "${id}" -lt "${SELF_ID}" ]]; then
    cancel_build "${id}" "${SELF_ID}"   # older run → kill it; this run is newer
  elif [[ "${id}" -gt "${SELF_ID}" && "${id}" -gt "${newest_other}" ]]; then
    newest_other="${id}"                # track the newest run that supersedes us
  fi
done

if [[ "${newest_other}" -gt 0 ]]; then
  echo ">>> a newer run (${newest_other}) for this PR already exists — self-cancelling build ${SELF_ID}."
  cancel_build "${SELF_ID}" "${newest_other}"
  # Fail this job so the pipeline stops here instead of doing real work while cancellation lands.
  exit 1
fi

echo ">>> this is the newest run for the PR — proceeding."
exit 0

