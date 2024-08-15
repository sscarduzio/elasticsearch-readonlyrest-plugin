#!/bin/bash -e

cd "$(dirname "$0")"

if [[ -z "$ECK_VERSION" ]]; then
  echo "ECK_VERSION is not defined"
  exit 1
fi

kubectl create -f "https://download.elastic.co/downloads/eck/$ECK_VERSION/crds.yaml"
kubectl apply -f "https://download.elastic.co/downloads/eck/$ECK_VERSION/operator.yaml"
