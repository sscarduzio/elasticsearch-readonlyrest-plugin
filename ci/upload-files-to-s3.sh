#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 file1 [file2 ... fileN] destination"
    exit 1
fi

DEST="${!#}"

for ((i = 1; i < $#; i++)); do
  FILE="${!i}"

  echo "Uploading $FILE to $DEST ..."
  upload "$1" "$2"
done

echo "DONE"