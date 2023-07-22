#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/es_8.8.2_ror_1.50.0-pre2:latest -f Dockerfile-es .