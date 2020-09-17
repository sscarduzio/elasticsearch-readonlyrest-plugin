#!/bin/bash

if [ $# -ne 1 ]
  then
    echo "$0 - This command accepts 1 argument: ES version \ni.e. :\n$0 v7.9.0)"
    exit 1
fi

TEMPDIR=`mktemp -d -t ror-ci-es-actions-$ES_VERSION-XXXXXXXXXX`
ES_VERSION=$1

git clone --quiet --branch $ES_VERSION --depth 1 "https://github.com/elastic/elasticsearch" $TEMPDIR
egrep -ri --include="*.java" ".*public.*static.*final.*String.*:[a-z]+/.*" $TEMPDIR |grep -v mock | awk -F= '{print $2}'  |tr ';' ' '| awk '{print $1}' |sort 
rm -rf $TEMPDIR

# No logging to stdout because this command is supposed to be used with pipelines.
