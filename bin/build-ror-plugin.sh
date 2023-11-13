#!/bin/bash -ex

if [[ $# -eq 0 ]] ; then
    echo "Please pass to the script an ES version you'd like to build ROR for"
    exit 1
fi

cd "$(dirname "$0")"

mkdir -p ../builds
rm -r ../builds/* || true

for arg in "$@"; do
  ES_VERSION=$arg

  ES_MODULE=$(ls | \
    grep -Ei '^es[0-9]+x$' | \
    sed "s/^es\([0-9]\)\([0-9]*\)x$/\1.\2.0 $ES_VERSION es\1\2x/" | \
    sort -Vr | \
    awk '{ print (system("bash -c \"pwd\"")) }' | \
    awk '{ if (system("bash -c \"pwd && . /ror/bin/test.sh && vergte \"" $2 " " $1) == 0) { print $3 } }' | \
    head -1)

  echo "Building ROR for ES_VERSION: $ES_VERSION (using ES_MODULE: $ES_MODULE):"

  ./gradlew clean --stacktrace $ES_MODULE:ror '-PesVersion='$ES_VERSION

  cp $ES_MODULE/build/distributions/readonlyrest-*es$ES_VERSION.zip* builds
done
