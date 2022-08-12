#!/bin/bash -e

docker-compose up -d --build --remove-orphans --force-recreate
docker-compose logs -f > elastic-cloud.log 2>&1 &