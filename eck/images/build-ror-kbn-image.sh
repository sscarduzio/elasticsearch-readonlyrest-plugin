#!/bin/bash -e

docker buildx build --platform=linux/arm64,linux/amd64 --push -t coutopl/kbn_8.8.2_ror_1.49.1:latest -f Dockerfile-kbn .
