#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 file1 [file2 ... fileN] destination"
    exit 1
fi

DEST="${!#}"

# Which store to upload to: ARTIFACTS (default) or LIBS. Selects ROR_<STORE>_STORE_* vars.
STORE="${ROR_S3_TARGET_STORE:-ARTIFACTS}"

for ((i = 1; i < $#; i++)); do
  FILE="${!i}"

  echo "Uploading $FILE to $DEST (store: $STORE) ..."
  upload_using_aws_s3_uploader "$FILE" "$DEST" "$STORE"
done

echo "DONE"