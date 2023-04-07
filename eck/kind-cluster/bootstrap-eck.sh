#!/bin/bash -e

kubectl create -f https://download.elastic.co/downloads/eck/2.6.2/crds.yaml
kubectl apply -f https://download.elastic.co/downloads/eck/2.6.2/operator.yaml
