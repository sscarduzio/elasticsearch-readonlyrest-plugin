#!/bin/bash -e

source "$(dirname "$0")/ci-lib.sh"

TAG="v1.55.0-pre3_es8.11.4"
if checkTagNotExist "$TAG"; then
  echo "not exists"
else
  echo "exists"
fi