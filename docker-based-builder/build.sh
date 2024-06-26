#!/bin/bash -e

cd "$(dirname "$0")"

if [[ $# -eq 0 ]] ; then
    echo "Please pass to the script ES versions you'd like to build ROR for"
    exit 1
fi

ES_VERSIONS=$*

docker buildx build --no-cache --progress=plain --load -t ror-builder-tmp -f ror-builder/Dockerfile-ror-builder ../

cleanup() {
  docker rmi -f ror-builder-tmp
}

trap cleanup EXIT

docker run --rm -v "$(pwd)"/../builds:/ror/builds ror-builder-tmp "$ES_VERSIONS"