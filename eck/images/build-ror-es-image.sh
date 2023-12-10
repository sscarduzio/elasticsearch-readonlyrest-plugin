#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/es_8.11.1_ror_1.53.0:latest -f Dockerfile-es .