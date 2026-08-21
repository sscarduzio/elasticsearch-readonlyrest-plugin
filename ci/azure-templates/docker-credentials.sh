#!/bin/bash
# Azure only. An Azure step sources this file before ci/configure-docker.sh. It goes away with the
# rest of the Azure pipeline.
#
# It maps the two Azure variables onto the names configure-docker.sh reads, and it drops a value
# that Azure did not resolve.
#
# Azure leaves a variable that no group defines as the literal text `$(DOCKER_HUB_USER)`. That text
# is not empty, so configure-docker.sh would take it for a credential, fail the login, and stop the
# job. A pull request from a fork links no variable group and always gets that text. With this file
# the job sees no credentials and pulls anonymously, which is what DOCKER_AUTH_REQUIRED=false asks
# for.
#
#   VAR_DOCKER_HUB_USER      DOCKER_REGISTRY_USER
#   VAR_DOCKER_HUB_RO_TOKEN  DOCKER_REGISTRY_PASSWORD

# Prints the value, or nothing when the value is still an Azure macro.
_ror_azure_docker_var() {
  case $1 in
    '$('*')') ;;
    *)        printf '%s' "$1" ;;
  esac
}

DOCKER_REGISTRY_USER=$(_ror_azure_docker_var "${VAR_DOCKER_HUB_USER:-}")
DOCKER_REGISTRY_PASSWORD=$(_ror_azure_docker_var "${VAR_DOCKER_HUB_RO_TOKEN:-}")
export DOCKER_REGISTRY_USER DOCKER_REGISTRY_PASSWORD

# `unset` gives this file the status 0, so a caller with `set -e` goes on.
unset -f _ror_azure_docker_var
