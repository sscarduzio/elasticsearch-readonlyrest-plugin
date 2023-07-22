#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 . -t kbn_8.8.2_ror_1.49.1 -f Dockerfile-kbn
docker tag kbn_8.8.2_ror_1.49.1 coutopl/kbn_8.8.2_ror_1.49.1
docker push coutopl/kbn_8.8.2_ror_1.49.1:latest
