#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/es_7.17.11_ror_1.50.0:latest -f Dockerfile-es .