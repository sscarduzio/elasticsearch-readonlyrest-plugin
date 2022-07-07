#!/bin/bash -e

docker build . -t es_7.17.0_ror_1.42.0-eck-poc -f Dockerfile-es
docker tag es_7.17.0_ror_1.42.0-eck-poc coutopl/es_7.17.0_ror_1.42.0-eck-poc
docker push coutopl/es_7.17.0_ror_1.42.0-eck-poc:latest
