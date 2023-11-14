#!/bin/bash -e

if [[ $# -eq 0 ]] ; then
    echo "Please pass to the script an ES version you\'d like to build ROR for"
    exit 1
fi

cd "$(dirname "$0")/.."

mkdir -p builds
rm -r builds/* || true

for arg in "$@"; do
  ES_VERSION=$arg
  echo "Building ROR for ES_VERSION $ES_VERSION:"

  ./gradlew clean --stacktrace --info buildRorPlugin '-PesVersion='"$ES_VERSION"

  cp es*/build/distributions/readonlyrest-*es"$ES_VERSION".zip* builds
done
