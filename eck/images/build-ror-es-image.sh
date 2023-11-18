#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/es_8.10.4_ror_1.52.0:latest -f Dockerfile-es .