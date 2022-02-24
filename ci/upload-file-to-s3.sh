#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

echo ">>>>>> DO WE HAVE AWS CREDS?"
export |grep aws| cut -c12-40

echo "Uploading $1 to $2 ..."

upload $1 $2

echo "DONE"