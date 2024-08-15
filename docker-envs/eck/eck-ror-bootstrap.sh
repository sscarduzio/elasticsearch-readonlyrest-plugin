#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if ! command -v kind &> /dev/null; then
  echo "Cannot find 'kind' tool. Please follow the installation steps: https://github.com/kubernetes-sigs/kind#installation-and-usage"
  exit 1
fi

if ! command -v docker &> /dev/null; then
  echo "Cannot find 'docker'. Please follow the installation steps: https://docs.docker.com/engine/install/"
  exit 2
fi

show_help() {
  echo "Usage: ./eck-ror-bootstrap.sh --es <elasticsearch_version> --kbn <kibana_version> --eck <eck_version>"
  exit 1
}

export ES_VERSION=""
export KBN_VERSION=""
export ECK_VERSION="2.13.0"

while [[ $# -gt 0 ]]; do
  case $1 in
  --es)
    if [[ -n $2 && $2 != --* ]]; then
      ES_VERSION="$2"
      shift 2
    else
      echo "Error: --es requires a version argument"
      show_help
    fi
    ;;
  --kbn)
    if [[ -n $2 && $2 != --* ]]; then
      KBN_VERSION="$2"
      shift 2
    else
      echo "Error: --kbn requires a version argument"
      show_help
    fi
    ;;
  --eck)
      if [[ -n $2 && $2 != --* ]]; then
        ECK_VERSION="$2"
        shift 2
      else
        echo "Error: --eck requires a version argument"
        show_help
      fi
      ;;
  *)
    echo "Unknown option: $1"
    show_help
    ;;
  esac
done

if [[ -z $ES_VERSION || -z $KBN_VERSION ]]; then
  echo "Error: Both --es and --kbn arguments are required"
  show_help
fi

echo "CONFIGURING K8S CLUSTER ..."
kind create cluster --name ror-eck --config kind-cluster/kind-cluster-config.yml
docker exec ror-eck-control-plane /bin/bash -c "sysctl -w vm.max_map_count=262144"
docker exec ror-eck-worker        /bin/bash -c "sysctl -w vm.max_map_count=262144"
docker exec ror-eck-worker2       /bin/bash -c "sysctl -w vm.max_map_count=262144"

echo "CONFIGURING ECK $ECK_VERSION ..."
docker cp kind-cluster/bootstrap-eck.sh ror-eck-control-plane:/
docker exec ror-eck-control-plane chmod +x bootstrap-eck.sh
docker exec ror-eck-control-plane bash -c "export ECK_VERSION=$ECK_VERSION && ./bootstrap-eck.sh"

echo "CONFIGURING ES $ES_VERSION AND KBN $KBN_VERSION WITH ROR ..."

SUBSTITUTED_DIR="kind-cluster/subst-ror"
cleanup() {
  rm -rf "$SUBSTITUTED_DIR"
}

trap cleanup EXIT
mkdir -p "$SUBSTITUTED_DIR"

for file in kind-cluster/ror/*.yml; do
  FILENAME=$(basename "$file")
  if [[ "$FILENAME" == "es.yml" || "$FILENAME" == "kbn.yml" ]]; then
    envsubst < "$file" > "$SUBSTITUTED_DIR/$FILENAME"
  else
    cp "$file" "$SUBSTITUTED_DIR"
  fi
done

docker cp "$SUBSTITUTED_DIR" ror-eck-control-plane:/ror/
docker exec ror-eck-control-plane bash -c 'cd ror && ls | xargs -n 1 kubectl apply -f'

echo ""
echo "------------------------------------------"
echo "ECK and ROR is being bootstrapped. Wait for all pods to be run and then open your browser and try to access https://localhost:15601/ (credentials admin:admin)"
echo ""

docker exec -ti ror-eck-control-plane kubectl get pods --watch
