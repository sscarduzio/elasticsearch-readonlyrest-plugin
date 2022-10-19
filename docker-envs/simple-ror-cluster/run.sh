#!/bin/bash -e

read -p "Build ROR from sources? [y/n]" choice
case "$choice" in
  y|Y )
    docker build --no-cache --progress=plain -t ror-builder ../../
    export DOCKERFILE="Dockerfile-build-for-from-sources"
    ;;
  * )
    export DOCKERFILE="Dockerfile-use-ror-binaries-from-api"
    ;;
esac

docker-compose up --build --remove-orphans --force-recreate