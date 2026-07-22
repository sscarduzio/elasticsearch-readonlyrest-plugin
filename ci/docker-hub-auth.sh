#!/bin/bash
# Authenticated Docker Hub pulls for jobs that start testcontainers (integration AND unit tests pull
# images — osixia/openldap, wiremock, toxiproxy, …). Anonymous pulls share one per-IP rate limit
# across runners behind shared egress, so a busy CI window hits "toomanyrequests".
#
# MUST be SOURCED (not executed) so the export lands in the caller's shell that runs the tests:
#   source ci/docker-hub-auth.sh
#
# Reusable across the IT and unit-test legs. Opt-in: a no-op when DOCKER_HUB_USER / DOCKER_HUB_RO_TOKEN
# aren't both set. Critically, in the no-op case it leaves DOCKER_AUTH_CONFIG UNSET (never empty) —
# testcontainers' RegistryAuthLocator does `if (getenv != null) ObjectMapper.readTree(...)`, so an
# empty string would throw a JSON parse error; only a genuinely absent var is skipped cleanly.
#
# Inputs are read from the env:
#   DOCKER_HUB_USER, DOCKER_HUB_RO_TOKEN
if [ -n "${DOCKER_HUB_RO_TOKEN:-}" ] && [ -n "${DOCKER_HUB_USER:-}" ]; then
  export DOCKER_AUTH_CONFIG="{\"auths\":{\"https://index.docker.io/v1/\":{\"auth\":\"$(printf '%s:%s' "$DOCKER_HUB_USER" "$DOCKER_HUB_RO_TOKEN" | base64 -w0)\"}}}"
  # Redact from logs (the value is base64(user:token)).
  echo "::add-mask::$DOCKER_AUTH_CONFIG"
  echo "[TEST] Docker Hub authenticated pulls ENABLED (user '$DOCKER_HUB_USER')"
else
  echo "[TEST] Docker Hub authenticated pulls DISABLED (anonymous, rate-limited) — DOCKER_HUB_USER/DOCKER_HUB_RO_TOKEN not both set"
fi
