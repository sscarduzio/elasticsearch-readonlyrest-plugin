#!/bin/bash -e

docker build . -t es_7.15.0_ror_1.38.0-eck-poc -f Dockerfile-es
docker tag es_7.15.0_ror_1.38.0-eck-poc coutopl/es_7.15.0_ror_1.38.0-eck-poc
docker push coutopl/es_7.15.0_ror_1.38.0-eck-poc:latest
