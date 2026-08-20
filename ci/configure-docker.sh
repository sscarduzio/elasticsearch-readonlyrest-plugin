#!/bin/bash
# Configures Docker for a CI job. This script is the only place that does it. Every job that pulls
# or pushes a Docker Hub image uses it.
#
# It does two things: it points Docker at a pull-through cache, and it authenticates.
#
# HOW TO USE IT
# Source the script. Do not run it. The caller shell needs the variables that the script exports.
#
#   source ci/configure-docker.sh
#
# ---------------------------------------------------------------------------------------------
# PART 1: THE MIRROR
#
# Docker Hub rejects a pull with a bare `429 Too Many Requests` when the runner's IP address is
# over the abuse rate limit. Docker applies that limit per address and ignores the account, so a
# login does not prevent it. A CI runner shares its address with other tenants, and a neighbour can
# fill the bucket. Docker Hub also counts every pull against one account quota.
#
# mirror.gcr.io answers from a different host, which removes both pressures. It serves docker.io
# only. It cannot serve docker.elastic.co, and it cannot accept a push.
#
# Three clients pull images, and each needs its own setting. This script sets all three:
#   - the docker daemon    registry-mirrors in /etc/docker/daemon.json
#   - buildx and BuildKit  ROR_DOCKER_HUB_MIRROR, which the gradle build turns into a --config flag
#                          that points at build-base/buildkitd.toml
#   - testcontainers       TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX
#
# ROR_DOCKER_HUB_MIRROR=false switches the mirror off. The e2e job does that, because it pulls an
# image that the publish job pushed a moment before, and a cache can hold a stale answer.
#
# ---------------------------------------------------------------------------------------------
# PART 2: AUTHENTICATION
#
# Anonymous pulls share one small limit per IP address, and all agents share it. A busy CI window
# then fails with "toomanyrequests: You have reached your unauthenticated pull rate limit". The
# failure also stops jobs that did not cause it.
#
# The script authenticates both Docker clients from the same credentials:
#   - the docker CLI, with a login (pull, push, manifest inspect, buildx, compose)
#   - testcontainers, with the DOCKER_AUTH_CONFIG variable
# Both halves are necessary. The docker CLI does not read DOCKER_AUTH_CONFIG, so a job that only
# exports that variable makes anonymous pulls from the CLI.
#
# The job supplies one pair of credentials:
#   DOCKER_REGISTRY_USER / DOCKER_REGISTRY_PASSWORD
#
# FAILURE
# The script never falls back to anonymous pulls when the job supplied credentials. If it cannot
# use them, it stops the job. An expired token thus fails at once, in the job that owns it. It
# does not become a rate-limit failure in a different job one hour later.
#
# To stop the job, the script exits the caller shell. It does not depend on `set -e` in the step,
# because a step without errexit would ignore the status and continue with anonymous pulls.
#
# DOCKER_AUTH_REQUIRED applies to one case only: the job supplied no credentials at all.
#   true (default)   The script stops the job. Any value other than "false" has this effect, so
#                    an empty value or a typing error also stops the job.
#   false            The script continues, and its pulls are anonymous. A job sets this when it
#                    must run without secrets, as a pull request from a fork must.
#
# A mirror failure never stops the job. Only an authentication failure does.
#
# ---------------------------------------------------------------------------------------------
# TWO LIMITS
# 1. This script cannot configure the job `container:` image. The runner pulls that image before
#    step 1 starts, so no step can set a mirror or a login first. The `credentials:` block on each
#    container does the login instead, and no mirror can reach that pull.
# 2. Docker CLI 27, which the toolchains image contains, ignores DOCKER_AUTH_CONFIG. This is why
#    the login is necessary. A later CLI reads the variable and gives it priority over the login.
#    That priority is safe here, because both halves use the same credentials. It is not safe if
#    you add a second login with different credentials. Use this script instead.

ROR_DOCKER_HUB_MIRROR_HOST="mirror.gcr.io"

# The daemon runs on the host. A container job reaches it through the mounted socket and cannot
# restart it, so this is best effort: it stops at the first thing it cannot do. The daemon also
# falls back to Docker Hub by itself, so a job without a daemon mirror still works.
_ror_docker_mirror_daemon() {
  command -v sudo >/dev/null 2>&1 || return 0
  sudo -n true 2>/dev/null || return 0
  # No systemd means no host daemon to restart. This is how a container job leaves early.
  sudo -n systemctl is-active --quiet docker 2>/dev/null || return 0

  sudo -n mkdir -p /etc/docker || return 0
  echo "{\"registry-mirrors\":[\"https://${ROR_DOCKER_HUB_MIRROR_HOST}\"]}" |
    sudo -n tee /etc/docker/daemon.json >/dev/null || return 0

  if sudo -n systemctl restart docker &&
     timeout 60 bash -c 'until docker info >/dev/null 2>&1; do sleep 2; done'; then
    echo "[CI] The docker daemon now mirrors Docker Hub."
    return 0
  fi

  echo "[CI] The docker daemon refused the mirror, so its own pulls go to Docker Hub."
  sudo -n rm -f /etc/docker/daemon.json
  sudo -n systemctl restart docker || true
  return 0
}

# Always returns 0. A mirror is an optimisation, and it must never stop a job.
_ror_docker_mirror() {
  if [ "${ROR_DOCKER_HUB_MIRROR:-true}" = "false" ]; then
    echo "[CI] Docker Hub mirror is OFF. Every pull goes to Docker Hub."
    return 0
  fi

  # The gradle build reads the first variable, testcontainers reads the second.
  export ROR_DOCKER_HUB_MIRROR="true"
  export TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX="${ROR_DOCKER_HUB_MIRROR_HOST}/"

  # A step is its own process, so give both values to the later steps of the job as well.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    {
      echo "ROR_DOCKER_HUB_MIRROR=true"
      echo "TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=${ROR_DOCKER_HUB_MIRROR_HOST}/"
    } >> "$GITHUB_ENV"
  fi

  _ror_docker_mirror_daemon
  echo "[CI] Docker Hub mirror is ON: ${ROR_DOCKER_HUB_MIRROR_HOST}."
  return 0
}

# A secret that the workflow does not define expands to the empty string, so an empty value means
# the job supplied nothing.
_ror_docker_auth_isset() {
  [ -n "$1" ]
}

# The job supplied no credentials. DOCKER_AUTH_REQUIRED decides if the job continues, and the
# job stops by default.
_ror_docker_auth_no_credentials() {
  echo "[CI] Docker authentication is OFF. Cause: $1"
  # Compare against "false", not "true", so that an unset variable stops the job. Every other value,
  # "False" and a typing error alike, also stops it: the safe direction is to stop.
  if [ "${DOCKER_AUTH_REQUIRED:-true}" != "false" ]; then
    # ::error:: puts an annotation on the job, as the inline logins did before.
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error::Docker authentication failed: $1"
    echo "[CI] DOCKER_AUTH_REQUIRED is not false, so the job stops here." >&2
    return 1
  fi
  echo "[CI] The job continues. Its pulls are anonymous, and Docker Hub limits their rate."
  return 0
}

# The job supplied credentials, but the script cannot use them. The job always stops here.
# DOCKER_AUTH_REQUIRED does not apply, because an anonymous pull must never hide a broken login.
_ror_docker_auth_failed() {
  echo "[CI] Docker authentication FAILED. Cause: $1" >&2
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error::Docker authentication failed: $1"
  return 1
}

_ror_docker_auth() {
  local user token auth

  if ! _ror_docker_auth_isset "${DOCKER_REGISTRY_USER:-}" ||
     ! _ror_docker_auth_isset "${DOCKER_REGISTRY_PASSWORD:-}"; then
    _ror_docker_auth_no_credentials "the job supplied no credentials (DOCKER_REGISTRY_USER and DOCKER_REGISTRY_PASSWORD)"
    return $?
  fi
  user=$DOCKER_REGISTRY_USER
  token=$DOCKER_REGISTRY_PASSWORD

  # Set DOCKER_AUTH_CONFIG here only. When the script finds no credentials, the variable must stay
  # unset, and it must not become empty. testcontainers reads the value as JSON if the value is not
  # null, and an empty value causes a parse error.
  #
  # `base64 -w0` gives one line, but -w is GNU only, and the BSD base64 of macOS refuses it. This
  # file also runs by hand, on a developer machine, so `tr` removes the line breaks instead.
  auth=$(printf '%s:%s' "$user" "$token" | base64 | tr -d '\n')
  export DOCKER_AUTH_CONFIG="{\"auths\":{\"https://index.docker.io/v1/\":{\"auth\":\"$auth\"}}}"

  # Hide the value from the log first, because it contains base64(user:token). Mask before anything
  # can print it, and thus before the login below.
  #
  # Outside GitHub Actions (a developer running this by hand) this is skipped. The export above still
  # stands, so the shell that sourced the file is authenticated.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::add-mask::$auth"
    echo "::add-mask::$DOCKER_AUTH_CONFIG"
  fi

  if ! command -v docker >/dev/null 2>&1; then
    _ror_docker_auth_failed "the docker CLI is not in the PATH, so the script cannot log it in"
    return $?
  fi

  # Discard the standard output. It holds only "Login Succeeded" and a warning about the credential
  # store. Keep the error output, because it shows the cause of a failure. Neither one holds the
  # token.
  if ! printf '%s' "$token" | docker login -u "$user" --password-stdin >/dev/null; then
    _ror_docker_auth_failed "the docker login failed for the user '$user'"
    return $?
  fi

  # Give the value to the later steps of the job, and only now. A step is its own process, so the
  # export above dies with the step that sourced this file. A job that sources the file in a step of
  # its own therefore needs this write, or testcontainers in a later step reads no value and pulls
  # anonymously while the log says authentication is ON.
  #
  # This comes after the login on purpose. The script stops the caller shell when the login fails,
  # but a step with `continue-on-error: true` lets the job go on, and GITHUB_ENV outlives the step.
  # With this order, a variable that is set means a login that succeeded.
  #
  # The value holds no newline, because `tr` removed them, so the NAME=value form is enough here.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "DOCKER_AUTH_CONFIG=$DOCKER_AUTH_CONFIG" >> "$GITHUB_ENV"
  fi

  echo "[CI] Docker authentication is ON. User '$user'. The docker CLI and testcontainers use them."
  return 0
}

# The mirror comes first, so that a pull in this same step already uses it. Its status is ignored
# on purpose: only authentication can stop the job.
_ror_docker_configure() {
  _ror_docker_mirror
  _ror_docker_auth
}

# The script is sourced, so `return` gives the status to the caller shell. That stops the step
# under `set -e`, which the GitHub Actions bash shell sets. It does not stop a caller without
# errexit, which would go on to pull anonymously, so exit also. An interactive shell is the one
# exception, where exit would close the terminal.

# Stop the trace of commands while this file runs. Some callers set the xtrace option. The trace of
# the login command would then put the token in the log.
_ror_docker_auth_xtrace=0
case $- in *x*) _ror_docker_auth_xtrace=1; set +x ;; esac

if ! _ror_docker_configure; then
  case $- in
    *i*) return 1 ;;
    *)   exit 1 ;;
  esac
fi

if [ "$_ror_docker_auth_xtrace" = 1 ]; then set -x; fi
# `unset` gives this file the status 0. Do not end the file with a test. A test gives the status 1
# when the trace was off, and a caller with `set -e` then stops.
unset _ror_docker_auth_xtrace
