#!/bin/bash -e

if ! command -v kind &> /dev/null
then
    echo "Cannot find 'kind' tool. Please follow the installation steps: https://github.com/kubernetes-sigs/kind#installation-and-usage" 
    exit 1
fi

if ! command -v docker &> /dev/null
then
  echo "Cannot find 'docker'. Please follow the installation steps: https://docs.docker.com/engine/install/"
  exit 2
fi

echo "CONFIGURING K8S CLUSTER ..."
kind create cluster --name ror-eck --config kind-cluster/kind-cluster-config.yml

echo "CONFIGURING ECK ..."
docker cp kind-cluster/bootstrap-eck.sh ror-eck-control-plane:/
docker exec ror-eck-control-plane chmod +x bootstrap-eck.sh
docker exec ror-eck-control-plane ./bootstrap-eck.sh

echo "CONFIGURING ES AND KBN WITH ROR ..."
docker cp kind-cluster/ror ror-eck-control-plane:/
docker exec ror-eck-control-plane bash -c 'cd ror && ls | xargs -n 1 kubectl apply -f'

echo ""
echo "------------------------------------------"
echo "ECK and ROR is being bootstrapped. Wait for all pods to be run and then open your browser and try to access https://localhost:15601/ (credentials admin:container)"
echo ""

docker exec -ti ror-eck-control-plane kubectl get pods --watch
