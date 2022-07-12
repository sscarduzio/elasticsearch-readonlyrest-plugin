#!/bin/bash

docker-compose stop
docker-compose rm
docker rmi -f ror-builder