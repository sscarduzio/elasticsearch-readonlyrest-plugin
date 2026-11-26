#!/usr/bin/env bash
# Exits 0 when HEAD is the current head of develop on origin, else 1. Only that commit may move <prefix>-develop.
# The rule lives here, not in a workflow line, because a PR runs its own copy of the workflow files.
set -euo pipefail
cd "$(dirname "$0")/../.."
develop_head=$(git ls-remote origin refs/heads/develop | cut -f1)
[ -n "$develop_head" ] && [ "$develop_head" = "$(git rev-parse HEAD)" ]
