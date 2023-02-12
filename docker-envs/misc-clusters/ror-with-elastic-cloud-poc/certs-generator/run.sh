#!/bin/bash -e

./clean.sh

mkdir output
docker-compose --file generate-certs-docker-compose.yml up --build --no-deps