#!/usr/bin/env bash
# Tests for ci/configure-docker.sh (Docker Hub + optional GHCR authentication).

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT="$SCRIPT_DIR/../.."
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

make_stubs() {
  local dir="$TEST_ROOT/$RANDOM"
  mkdir -p "$dir/bin"
  cat > "$dir/bin/docker" <<'EOF'
#!/usr/bin/env bash
stdin_val=$(cat)
echo "docker $* stdin=$stdin_val" >> "$TEST_LOG"
if [ "${FAIL_GHCR:-0}" = 1 ] && [[ "$*" == *"ghcr.io"* ]]; then
  echo "unauthorized" >&2
  exit 1
fi
echo "Login Succeeded"
EOF
  chmod +x "$dir/bin/docker"
  : > "$dir/log"
  echo "$dir"
}

test_docker_hub_only() {
  local dir out
  dir=$(make_stubs)
  out=$(
    PATH="$dir/bin:$PATH" TEST_LOG="$dir/log" \
    DOCKER_REGISTRY_USER=hubuser DOCKER_REGISTRY_PASSWORD=hubpass \
    env -u GHCR_TOKEN -u GITHUB_ACTIONS \
    bash -c 'source "$1/ci/configure-docker.sh" && printf "%s" "$DOCKER_AUTH_CONFIG"' _ "$REPO_ROOT"
  )
  grep -qx 'docker login -u hubuser --password-stdin stdin=hubpass' "$dir/log" \
    || fail "expected Docker Hub login: $(cat "$dir/log")"
  [ "$(wc -l < "$dir/log")" -eq 1 ] || fail "unexpected extra logins: $(cat "$dir/log")"
  echo "$out" | jq -e '.auths["https://index.docker.io/v1/"].auth and (.auths["ghcr.io"] | not)' >/dev/null \
    || fail "unexpected DOCKER_AUTH_CONFIG: $out"
}

test_docker_hub_and_ghcr() {
  local dir out
  dir=$(make_stubs)
  out=$(
    PATH="$dir/bin:$PATH" TEST_LOG="$dir/log" \
    DOCKER_REGISTRY_USER=hubuser DOCKER_REGISTRY_PASSWORD=hubpass GHCR_TOKEN=ghpat \
    env -u GITHUB_ACTIONS \
    bash -c 'source "$1/ci/configure-docker.sh" && printf "%s" "$DOCKER_AUTH_CONFIG"' _ "$REPO_ROOT"
  )
  grep -qx 'docker login -u hubuser --password-stdin stdin=hubpass' "$dir/log" \
    || fail "expected Docker Hub login: $(cat "$dir/log")"
  grep -qx 'docker login ghcr.io -u x-access-token --password-stdin stdin=ghpat' "$dir/log" \
    || fail "expected GHCR login: $(cat "$dir/log")"
  echo "$out" | jq -e '.auths["https://index.docker.io/v1/"].auth and .auths["ghcr.io"].auth' >/dev/null \
    || fail "expected both registries in DOCKER_AUTH_CONFIG: $out"
}

test_failed_ghcr_login_stops_job() {
  local dir status=0
  dir=$(make_stubs)
  PATH="$dir/bin:$PATH" TEST_LOG="$dir/log" FAIL_GHCR=1 \
    DOCKER_REGISTRY_USER=hubuser DOCKER_REGISTRY_PASSWORD=hubpass GHCR_TOKEN=badpat \
    env -u GITHUB_ACTIONS \
    bash -c 'source "$1/ci/configure-docker.sh"' _ "$REPO_ROOT" >/dev/null 2>&1 || status=$?
  [ "$status" -eq 1 ] || fail "expected exit 1 on failed GHCR login, got $status"
}

for t in $(declare -F | awk '{print $3}' | grep '^test_'); do
  "$t"
  echo "ok - $t"
done
