#!/bin/bash

source "$(dirname "$0")/ci-lib.sh"

UNIT_TEST_REPORT=$(find . | grep report | grep -v integration | grep index.html)
INT_TEST_REPORT=$(find . | grep report | grep integration | grep index.html)

if [ ! -z "$UNIT_TEST_REPORT" ]; then
  echo "found unit test report UNIT_TEST_REPORT"
  S3_PATH="test_reports/$(date +%Y-%m-%d_%H.%M)$ROR_TASK-unit.html"
  upload_using_deltaglider_uploader $UNIT_TEST_REPORT $S3_PATH
fi


if [ ! -z "$INT_TEST_REPORT" ]; then
  echo "found integration test report $INT_TEST_REPORT"
  S3_PATH="test_reports/$(date +%Y-%m-%d_%H.%M)_$ROR_TASK-integration.html"
  upload_using_deltaglider_uploader $INT_TEST_REPORT $S3_PATH
fi

