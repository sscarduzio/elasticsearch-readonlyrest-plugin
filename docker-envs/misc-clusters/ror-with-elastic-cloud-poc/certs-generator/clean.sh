#!/bin/bash -e

docker-compose --file generate-certs-docker-compose.yml down -v
rm -rf output input