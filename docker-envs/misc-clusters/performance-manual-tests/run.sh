#!/bin/bash -e

rm -rf .base
cp -R ../../base-ror-docker .base
./.base/run.sh