#!/usr/bin/env bash
# Runs ci/toolchains/is-develop-head.sh in a clone of a local origin. Only the head of develop passes.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TOOL="$SCRIPT_DIR/../../../ci/toolchains/is-develop-head.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

fail() { echo "FAIL: $*" >&2; exit 1; }

git init --quiet --bare "$TEST_ROOT/origin.git"
git init --quiet -b develop "$TEST_ROOT/work"
mkdir -p "$TEST_ROOT/work/ci/toolchains"
cp "$TOOL" "$TEST_ROOT/work/ci/toolchains/is-develop-head.sh"
cd "$TEST_ROOT/work"
git remote add origin "$TEST_ROOT/origin.git"
git add . && git commit --quiet -m develop
git push --quiet origin develop
develop_sha=$(git rev-parse HEAD)

check() { # <expected: 0|1> <case>
  local code=0
  ci/toolchains/is-develop-head.sh || code=$?
  [ "$code" = "$1" ] || fail "$2: exit $code, expected $1"
}

git checkout --quiet --detach "$develop_sha"
check 0 "HEAD is the head of develop"

git checkout --quiet -b pr
git commit --quiet --allow-empty -m pr
git push --quiet origin pr
check 1 "HEAD is a PR commit"

git checkout --quiet --detach "$develop_sha"
git checkout --quiet develop
git commit --quiet --allow-empty -m newer
git push --quiet origin develop
git checkout --quiet --detach "$develop_sha"
check 1 "HEAD is an older commit of develop"

git push --quiet origin --delete develop
check 1 "origin has no develop"

echo "PASS: is-develop-head"
