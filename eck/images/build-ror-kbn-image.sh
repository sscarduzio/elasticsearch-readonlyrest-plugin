#!/bin/bash -e

docker build . -t kbn_7.15.0_ror_1.38.0-eck-poc -f Dockerfile-kbn
docker tag kbn_7.15.0_ror_1.38.0-eck-poc coutopl/kbn_7.15.0_ror_1.38.0-eck-poc
docker push coutopl/kbn_7.15.0_ror_1.38.0-eck-poc:latest
