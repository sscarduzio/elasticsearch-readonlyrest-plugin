#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

echo "Uploading $1 to $2 ..."

upload $1 $2

echo "DONE"