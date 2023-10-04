#!/bin/bash -e

if [[ $# -eq 0 ]] ; then
    echo "Please pass to the script an ES version you'd like to build ROR for"
    exit 1
fi

ES_VERSIONS=$*

docker buildx build --no-cache --progress=plain --load -t ror-builder-tmp ../../
docker run --rm -v $(pwd)/builds:/ror/builds ror-builder-tmp $ES_VERSIONS
docker rmi -f ror-builder-tmp