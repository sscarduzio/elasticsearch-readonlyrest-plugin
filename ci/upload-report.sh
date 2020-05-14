#!/bin/bash

. ci/ci-lib.sh
ROR_TASK="ew77x_integration"
UNIT_TEST_REPORT=$(find . | grep report | grep -v integration | grep index.html)
INT_TEST_REPORT=$(find . | grep report | grep integration | grep index.html)

if [ ! -z "$UNIT_TEST_REPORT" ]; then
  echo "found unit test report UNIT_TEST_REPORT"
  upload $UNIT_TEST_REPORT "test_reports/$(date +%Y-%m-%d_%H.%M)$ROR_TASK-unit.html"
fi


if [ ! -z "$INT_TEST_REPORT" ]; then
  echo "found integration test report $INT_TEST_REPORT"
  upload $INT_TEST_REPORT "test_reports/$(date +%Y-%m-%d_%H.%M)_$ROR_TASK-integration.html"
fi

