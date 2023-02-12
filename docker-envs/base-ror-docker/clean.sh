#!/bin/bash -e

docker-compose rm --stop --force
docker rmi -f ror-builder