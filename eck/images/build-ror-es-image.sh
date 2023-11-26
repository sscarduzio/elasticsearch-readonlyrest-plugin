#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/es_8.7.1_ror_1.54.0-pre1:latest -f Dockerfile-es .