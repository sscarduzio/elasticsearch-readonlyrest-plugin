#!/bin/bash -e

echo "Preparing ES+KBN with ROR environment ..."

determine_ror_es_dockerfile () {
  read_es_version

  read -p "Use ES ROR:
 1. From API
 2. From FILE
 3. From SOURCES

Your choice: " choice

  case "$choice" in
    1 )
      export ES_DOCKERFILE="Dockerfile-use-ror-binaries-from-api"
      read_ror_es_version
      ;;
    2 )
      export ES_DOCKERFILE="Dockerfile-use-ror-binaries-from-file"
      read_es_ror_file_path
      ;;
    3 )
      docker build --no-cache --progress=plain -t ror-builder ../../../
      export ES_DOCKERFILE="Dockerfile-build-for-from-sources"
      ;;
    * )
      echo "There is no such option to pick. Closing ..."
      exit 1
      ;;
  esac
}

read_es_version () {
  read -p "Enter ES version: " esVersion
  if [[ -z "$esVersion" ]]; then
    echo "ES version is required and cannot be empty"
    exit 2
  else
    export ES_VERSION=$esVersion
  fi
}

read_ror_es_version () {
  read -p "Enter ROR ES version: " rorVersion
  if [[ -z "$rorVersion" ]]; then
    echo "ROR ES version is required and cannot be empty"
    exit 5
  else
    export ROR_ES_VERSION=$rorVersion
  fi
}

read_es_ror_file_path () {
  read -p "Enter ES ROR file path (it has to be placed in `dirname "$0"`): " path
  if [ -f "$path" ]; then
    export ES_ROR_FILE=$path
  else
    echo "Cannot find file $path"
    exit 3
  fi
}

determine_ror_kbn_dockerfile () {
  read_kbn_version

# todo: license

  read -p "Use KBN ROR:
 1. From API
 2. From FILE

Your choice: " choice

  case "$choice" in
    1 )
      export KBN_DOCKERFILE="Dockerfile-use-ror-binaries-from-api"
      read_ror_kbn_version
      ;;
    2 )
      export KBN_DOCKERFILE="Dockerfile-use-ror-binaries-from-file"
      read_kbn_ror_file_path
      ;;
    * )
      echo "There is no such option to pick. Closing ..."
      exit 1
      ;;
  esac
}

read_kbn_version () {
  read -p "Enter KBN version: " kbnVersion
  if [[ -z "$kbnVersion" ]]; then
    echo "KBN version is required and cannot be empty"
    exit 4
  else
    export KBN_VERSION=$kbnVersion
  fi
}

read_ror_kbn_version () {
  read -p "Enter ROR KBN version: " rorVersion
  if [[ -z "$rorVersion" ]]; then
    echo "ROR KBN version is required and cannot be empty"
    exit 5
  else
    export ROR_KBN_VERSION=$rorVersion
  fi
}

read_kbn_ror_file_path () {
  read -p "Enter KBN ROR file path (it has to be placed in `dirname "$0"`): " path
  if [ -f "$path" ]; then
    export KBN_ROR_FILE=$path
  else
    echo "Cannot find file $path"
    exit 6
  fi
}

echo "-----------------"
determine_ror_es_dockerfile
echo "-----------------"
determine_ror_kbn_dockerfile
echo "-----------------"

docker-compose up --build --remove-orphans --force-recreate
