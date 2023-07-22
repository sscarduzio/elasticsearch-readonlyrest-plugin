#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 . -t es_8.8.2_ror_1.50.0-pre2 -f Dockerfile-es
docker tag es_8.8.2_ror_1.50.0-pre2 coutopl/es_8.8.2_ror_1.50.0-pre2
docker push coutopl/es_8.8.2_ror_1.50.0-pre2:latest
