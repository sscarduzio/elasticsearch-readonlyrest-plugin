#!/bin/bash

# Upload Cypress failure artifacts (videos, screenshots, logs) to S3, so a red e2e job leaves
# something to look at after its runner is destroyed. Mirrors the ROR KBN repo's
# scripts/uploadCypressArtifactsToS3.sh; kept as a script (not a run-pipeline.sh task) because the
# workflow calls it from a separate `if: failure()` step, after run_e2e_tests has already returned.
#
# S3, not GitHub artifacts: those count against the metered Actions-storage quota.
#
# Usage: upload-cypress-artifacts-to-s3.sh <results dir> <s3 subfolder>
#   e.g. upload-cypress-artifacts-to-s3.sh "$E2E_TESTS_DIR/results" es818x_8.19.19
#
# Uploads to the E2E_REPORTS store — the bucket's `e2e_reports/` tree, a sibling of the `builds/`
# (ARTIFACTS) and `libs/` (LIBS) trees. Same env-var family as those, per ci-lib.sh's
# one credential set: ROR_S3_{ACCESS_KEY_ID,SECRET_ACCESS_KEY,BUCKET,
# REGION,ENDPOINT_URL,PATH_PREFIX}. It gets its own credentials because the gateway authorizes each
# prefix separately — the publishing creds are 403ed here, and these must not be able to write to
# the customer-facing builds/ tree.
#
# Final key: <PATH_PREFIX>/<s3 subfolder>/<path within results dir>.

# Deliberately no `-e`: one failed upload must not skip the remaining files.
set -uo pipefail

CI_DIR="$(cd "$(dirname "$0")" && pwd)"
STORE=E2E_REPORTS

# Credentials are flat; only the key prefix is resolved through the store name, as in ci-lib.sh
# (that helper takes one file with a guessed mime type; this one walks a tree and passes an explicit
# mime, so it drives ci/s3-uploader.sh itself rather than going through it).
AK_VAR="ROR_S3_ACCESS_KEY_ID"
SK_VAR="ROR_S3_SECRET_ACCESS_KEY"
BUCKET_VAR="ROR_S3_BUCKET"
REGION_VAR="ROR_S3_REGION"
ENDPOINT_VAR="ROR_S3_ENDPOINT_URL"
PREFIX_VAR="ROR_S3_PATH_${STORE}"

require_env() {
  if [ -z "${!1:-}" ]; then
    echo "ERROR: $1 is not set (required to upload the Cypress artifacts)"
    return 1
  fi
}

# REGION and ENDPOINT_URL are as mandatory as the credentials: without REGION the uploader silently
# signs for us-east-1, which the MinIO/DGP endpoint rejects, and without ENDPOINT_URL it addresses
# real AWS S3, where these credentials mean nothing. Both fail with opaque 403s, so fail early.
for VAR in "$AK_VAR" "$SK_VAR" "$BUCKET_VAR" "$REGION_VAR" "$ENDPOINT_VAR"; do
  require_env "$VAR" || exit 1
done

AK="${!AK_VAR}"
SK="${!SK_VAR}"
BUCKET="${!BUCKET_VAR}"
REGION="${!REGION_VAR}"
PATH_PREFIX="${!PREFIX_VAR:-}"

SOURCE_DIR="${1:?Usage: upload-cypress-artifacts-to-s3.sh <results dir> <s3 subfolder>}"
S3_SUBFOLDER="${2:?Usage: upload-cypress-artifacts-to-s3.sh <results dir> <s3 subfolder>}"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "No Cypress results directory at $SOURCE_DIR — nothing to upload."
  echo "(the suite may have failed before producing any, e.g. while the stack was starting)"
  exit 0
fi

S3_PATH="${PATH_PREFIX:+${PATH_PREFIX%/}/}${S3_SUBFOLDER%/}/"

# Consumed by s3-uploader.sh, which switches to path-style addressing when it is set.
export S3_ENDPOINT_URL="${!ENDPOINT_VAR}"

# An explicit mime type keeps the uploader from calling `file` (its guess path), which is not
# guaranteed to exist on a bare runner. Without it the Content-Type comes out empty, the signed-POST
# policy rejects the malformed form, and the upload 403s.
mime_of() {
  case "$1" in
    *.mp4) echo "video/mp4" ;;
    *.png) echo "image/png" ;;
    *.log | *.txt) echo "text/plain" ;;
    *.json) echo "application/json" ;;
    *.xml) echo "application/xml" ;;
    *) echo "application/octet-stream" ;;
  esac
}

upload_one() {
  local FILE=$1 KEY=$2 MIME=$3
  "$CI_DIR/s3-uploader.sh" "$AK" "$SK" "${BUCKET}@${REGION}" "$FILE" "$KEY" "$MIME"
}

UPLOADED=0
SKIPPED_EMPTY=0
FAILED=0
DIAGNOSED=false

# -print0/read -d '' so paths with spaces survive; the relative path becomes the key, keeping the
# videos/ vs screenshots/ split visible in the bucket.
while IFS= read -r -d '' FILE; do
  # Cypress leaves 0-byte *-compressed.mp4 placeholders for specs that passed. The signed-POST policy
  # enforces content-length-range >= 1, so uploading one is a guaranteed 403.
  if [ ! -s "$FILE" ]; then
    SKIPPED_EMPTY=$((SKIPPED_EMPTY + 1))
    continue
  fi

  REL=${FILE#"$SOURCE_DIR"/}
  MIME=$(mime_of "$FILE")
  if upload_one "$FILE" "${S3_PATH}${REL}" "$MIME"; then
    UPLOADED=$((UPLOADED + 1))
    continue
  fi

  echo "WARNING: upload failed for $FILE (continuing)"
  FAILED=$((FAILED + 1))
  # s3-uploader.sh uses `curl -f`, which hides the server's error body. On the FIRST failure only,
  # re-run it with S3_UPLOADER_DEBUG=1 to capture the actual S3 error XML (AccessDenied /
  # SignatureDoesNotMatch / a policy condition) — without it a 403 is unattributable.
  if [ "$DIAGNOSED" = false ]; then
    DIAGNOSED=true
    echo "----- S3 failure diagnostic: re-uploading $REL to capture the error body -----"
    (S3_UPLOADER_DEBUG=1 upload_one "$FILE" "${S3_PATH}${REL}" "$MIME" 2>&1 | sed 's/^/[s3-debug] /') || true
    echo "----- end diagnostic -----"
  fi
done < <(find "$SOURCE_DIR" -type f -print0)

echo "S3 upload summary: uploaded=$UPLOADED skipped_empty=$SKIPPED_EMPTY failed=$FAILED"
if [ "$UPLOADED" -gt 0 ]; then
  echo "Uploaded $UPLOADED Cypress artifact(s) to s3://${BUCKET}/${S3_PATH}"
elif [ "$FAILED" -eq 0 ]; then
  echo "No Cypress artifacts found in $SOURCE_DIR — nothing to upload."
fi
