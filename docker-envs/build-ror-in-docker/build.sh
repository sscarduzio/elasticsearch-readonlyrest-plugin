#!/bin/bash -e

if [[ $# -eq 0 ]] ; then
    echo "Please pass to the script an ES version you'd like to build ROR for"
    exit 1
fi

ES_VERSION=$1

docker build --no-cache --progress=plain -t ror-builder-tmp ../../
docker run --rm -v $(pwd)/builds:/ror/builds -e ES_VERSION=$ES_VERSION ror-builder-tmp
docker rmi -f ror-builder-tmp