#!/bin/bash -e

docker build . -t kbn_8.6.2_ror_1.47.0-eck-poc -f Dockerfile-kbn
docker tag kbn_8.6.2_ror_1.47.0-eck-poc coutopl/kbn_8.6.2_ror_1.47.0-eck-poc
docker push coutopl/kbn_8.6.2_ror_1.47.0-eck-poc:latest
