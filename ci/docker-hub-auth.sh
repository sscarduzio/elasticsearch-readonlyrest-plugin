#!/bin/bash
# Authenticated Docker Hub pulls for jobs that start testcontainers (integration AND unit tests pull
# images — osixia/openldap, wiremock, toxiproxy, …). Anonymous pulls share one per-IP rate limit
# across every Azure hosted agent, so a busy CI window hits "toomanyrequests".
#
# MUST be SOURCED (not executed) so the export lands in the caller's shell that runs the tests:
#   source ci/docker-hub-auth.sh
#
# Reusable across the IT and unit-test legs. Opt-in: a no-op when DOCKER_HUB_USER / DOCKER_HUB_RO_TOKEN
# aren't both set. Critically, in the no-op case it leaves DOCKER_AUTH_CONFIG UNSET (never empty) —
# testcontainers' RegistryAuthLocator does `if (getenv != null) ObjectMapper.readTree(...)`, so an
# empty string would throw a JSON parse error; only a genuinely absent var is skipped cleanly.
#
# Inputs are read from the env (the caller maps the Azure pipeline vars):
#   DOCKER_HUB_USER, DOCKER_HUB_RO_TOKEN
#
# An undefined Azure non-secret var expands to its literal "$(NAME)"; reject that form too.
if [ -n "${DOCKER_HUB_RO_TOKEN:-}" ] && [ "${DOCKER_HUB_RO_TOKEN}" != '$(DOCKER_HUB_RO_TOKEN)' ] \
   && [ -n "${DOCKER_HUB_USER:-}" ] && [ "${DOCKER_HUB_USER}" != '$(DOCKER_HUB_USER)' ]; then
  export DOCKER_AUTH_CONFIG="{\"auths\":{\"https://index.docker.io/v1/\":{\"auth\":\"$(printf '%s:%s' "$DOCKER_HUB_USER" "$DOCKER_HUB_RO_TOKEN" | base64 -w0)\"}}}"
  # Redact from logs (the value is base64(user:token)). Each CI has its own log command; emitting
  # the Azure ##vso line on GitHub Actions would PRINT the secret instead of masking it.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::add-mask::$DOCKER_AUTH_CONFIG"
  else
    echo "##vso[task.setvariable variable=DOCKER_AUTH_CONFIG;isSecret=true]$DOCKER_AUTH_CONFIG"
  fi
  echo "[TEST] Docker Hub authenticated pulls ENABLED (user '$DOCKER_HUB_USER')"
else
  echo "[TEST] Docker Hub authenticated pulls DISABLED (anonymous, rate-limited) — DOCKER_HUB_USER/DOCKER_HUB_RO_TOKEN not both set"
fi
