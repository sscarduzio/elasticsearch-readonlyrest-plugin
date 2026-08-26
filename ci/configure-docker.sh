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
# Three clients pull images, and each needs its own setting. The script exports what each needs:
#   - buildx and BuildKit  ROR_DOCKER_HUB_MIRROR, which the gradle build turns into a --config flag
#                          that points at build-base/buildkitd.toml
#   - the test suite       ROR_DOCKER_HUB_MIRROR_PREFIX, which DockerHubMirror reads. This rewrites
#                          the registry name directly, so these pulls cannot fall back to Docker Hub.
#   - the toolchains build the same variable, which build-toolchains-image.yml passes as the
#                          MIRROR build argument of ci/toolchains/JdkToolchains.Dockerfile.
#
# Do NOT set TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX here. testcontainers applies that prefix to every
# name without a registry host, and a locally built name looks the same as a Docker Hub name. It
# rewrote our own ror-it-es:<hash> image and then tried to pull it from the mirror, which fails with
# "manifest unknown". The test suite therefore names the images it mirrors, one by one.
#
# ROR_DOCKER_HUB_MIRROR=false switches the mirror off for one job. No job sets it. Use it when the
# mirror cannot serve an image that a job needs.
#
# A mirror never stops the job by itself. BuildKit keeps the docker.io image identity, so it falls
# back to Docker Hub. The two settings that rewrite the name, the test suite and the toolchains
# build, have no such fallback and can fail the job.
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
#   unset (default)  The script reads the event. A pull request from a fork continues, and its
#                    pulls are anonymous. GitHub gives such a run no secrets. Every other run stops.
#   false            The job continues. Its pulls are anonymous.
#   any other value  The job stops. "True", an empty value and a typing error all stop it.
#
# The workflow computed the fork case before. Two jobs held the same expression, and a new job had
# to copy it. The script reads the event instead.
#
# ---------------------------------------------------------------------------------------------
# TWO LIMITS
# 1. This script cannot configure the job `container:` image. The runner pulls it before step 1
#    starts. The setup job settles that pull instead. See ci/resolve-toolchains-image.sh.
# 2. Docker CLI 27, which the toolchains image contains, ignores DOCKER_AUTH_CONFIG. This is why
#    the login is necessary. A later CLI reads the variable and gives it priority over the login.
#    That priority is safe here, because both halves use the same credentials. It is not safe if
#    you add a second login with different credentials. Use this script instead.

ROR_DOCKER_HUB_MIRROR_HOST="mirror.gcr.io"

# Always returns 0. A mirror is an optimisation, and it must never stop a job.
_ror_docker_mirror() {
  if [ "$(echo "${ROR_DOCKER_HUB_MIRROR:-true}" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')" = "false" ]; then
    export ROR_DOCKER_HUB_MIRROR="false"
    unset ROR_DOCKER_HUB_MIRROR_PREFIX

    if [ -n "${GITHUB_ACTIONS:-}" ]; then
      {
        echo "ROR_DOCKER_HUB_MIRROR=false"
        echo "ROR_DOCKER_HUB_MIRROR_PREFIX="
      } >> "$GITHUB_ENV"
    fi

    echo "[CI] Docker Hub mirror is OFF."
    return 0
  fi

  # The gradle build reads the first variable, the test suite reads the second.
  export ROR_DOCKER_HUB_MIRROR="true"
  export ROR_DOCKER_HUB_MIRROR_PREFIX="${ROR_DOCKER_HUB_MIRROR_HOST}/"

  # A step is its own process, so give both values to the later steps of the job as well.
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    {
      echo "ROR_DOCKER_HUB_MIRROR=true"
      echo "ROR_DOCKER_HUB_MIRROR_PREFIX=${ROR_DOCKER_HUB_MIRROR_HOST}/"
    } >> "$GITHUB_ENV"
  fi

  echo "[CI] Docker Hub mirror is ON: ${ROR_DOCKER_HUB_MIRROR_HOST}."
  return 0
}

# A secret that the workflow does not define expands to the empty string, so an empty value means
# the job supplied nothing.
_ror_docker_auth_isset() {
  [ -n "$1" ]
}

# True when a pull request comes from a fork. GitHub gives such a run no secrets. The run cannot
# authenticate, and a stop would block every contribution from outside.
#
# The answer is false when the script cannot tell: a different event, no payload, no jq, or a file
# it cannot read. The job then needs credentials. That is the safe direction.
_ror_docker_auth_fork_pull_request() {
  local head_repo

  [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ] || return 1
  [ -n "${GITHUB_EVENT_PATH:-}" ] && [ -r "${GITHUB_EVENT_PATH}" ] || return 1
  command -v jq >/dev/null 2>&1 || return 1

  head_repo=$(jq -r '.pull_request.head.repo.full_name // empty' "$GITHUB_EVENT_PATH" 2>/dev/null)
  [ -n "$head_repo" ] || return 1
  [ "$head_repo" != "${GITHUB_REPOSITORY:-}" ]
}

# The job supplied no credentials. DOCKER_AUTH_REQUIRED decides if the job continues, and the
# job stops by default.
_ror_docker_auth_no_credentials() {
  local required=${DOCKER_AUTH_REQUIRED:-}

  echo "[CI] Docker authentication is OFF. Cause: $1"

  if [ -z "$required" ]; then
    if _ror_docker_auth_fork_pull_request; then
      echo "[CI] This is a pull request from a fork, and GitHub gives it no secrets."
      required=false
    else
      required=true
    fi
  fi

  # Compare against "false", not "true". Every other value stops the job, "False" and a typing
  # error included. The safe direction is to stop.
  if [ "$required" != "false" ]; then
    # ::error:: puts an annotation on the job, as the inline logins did before.
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error::Docker authentication failed: $1"
    echo "[CI] The job needs credentials, so it stops here." >&2
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
