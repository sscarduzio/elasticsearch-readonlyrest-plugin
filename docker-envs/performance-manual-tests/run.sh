#!/bin/bash -e

docker build --no-cache --progress=plain -t ror-builder ../../
docker-compose up -d --build --remove-orphans --force-recreate
docker-compose logs -f > ror.log 2>&1 &
