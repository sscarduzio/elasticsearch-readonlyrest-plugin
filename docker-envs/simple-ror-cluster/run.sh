#!/bin/bash -e

docker build --no-cache --progress=plain -t ror-builder ../../
docker-compose up --build --remove-orphans --force-recreate