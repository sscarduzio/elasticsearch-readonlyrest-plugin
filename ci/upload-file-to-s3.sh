#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

echo ">>>>>> DO WE HAVE AWS CREDS?"
echo "$aws_access_key_id" | cut -c1-6

echo "Uploading $1 to $2 ..."
upload $1 $2

echo "DONE"