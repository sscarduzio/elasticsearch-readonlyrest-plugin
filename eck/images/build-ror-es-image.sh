#!/bin/bash -e

docker build . -t es_8.7.0_ror_1.49.0-eck-poc -f Dockerfile-es
docker tag es_8.7.0_ror_1.49.0-eck-poc coutopl/es_8.7.0_ror_1.49.0-eck-poc
docker push coutopl/es_8.7.0_ror_1.49.0-eck-poc:latest
