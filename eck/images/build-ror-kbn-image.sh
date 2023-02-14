#!/bin/bash -e

docker build . -t kbn_8.6.1_ror_1.47.0-eck-poc -f Dockerfile-kbn
docker tag kbn_8.6.1_ror_1.47.0-eck-poc coutopl/kbn_9.6.1_ror_1.47.0-eck-poc
docker push coutopl/kbn_8.6.1_ror_1.47.0-eck-poc:latest
