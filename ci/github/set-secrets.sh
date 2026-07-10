#!/usr/bin/env bash
# Register the CI secrets/variables needed by the GitHub Actions port (see SECRETS.md).
# Fill in the values, then run:  ci/github/set-secrets.sh
# Re-runnable (gh overwrites). Nothing here is committed with a real value.
set -euo pipefail

REPO="${REPO:-sscarduzio/elasticsearch-readonlyrest-plugin}"

# --- helper: set only if a value is provided (skip blanks so you can do it in passes) ---
set_secret() { [ -n "${2:-}" ] && gh secret   set "$1" -R "$REPO" --body "$2" && echo "secret  $1" || echo "skip    $1 (empty)"; }
set_var()    { [ -n "${2:-}" ] && gh variable set "$1" -R "$REPO" --body "$2" && echo "var     $1" || echo "skip    $1 (empty)"; }

############################################
# SECRETS  (fill these in)
############################################

# S3 libs store
set_secret ROR_LIBS_STORE_ACCESS_KEY_ID        ""
set_secret ROR_LIBS_STORE_ACCESS_KEY_SECRET    ""
# S3 artifacts store
set_secret ROR_ARTIFACTS_STORE_ACCESS_KEY_ID     ""
set_secret ROR_ARTIFACTS_STORE_ACCESS_KEY_SECRET ""

# Docker registry (push) + Docker Hub (authenticated pulls)
set_secret DOCKER_REGISTRY_USER      ""
set_secret DOCKER_REGISTRY_PASSWORD  ""
set_secret DOCKER_HUB_USER           ""
set_secret DOCKER_HUB_RO_TOKEN       ""

# CVE / dependency-check
set_secret NVD_API_KEY         ""
set_secret OSS_INDEX_USERNAME  ""
set_secret OSS_INDEX_PASSWORD  ""

# Maven Central / Sonatype publishing
set_secret MAVEN_REPO_USER            ""
set_secret MAVEN_REPO_PASSWORD        ""
set_secret MAVEN_STAGING_PROFILE_ID   ""
set_secret GPG_KEY_ID                 ""
set_secret GPG_PASSPHRASE             ""

# Secure file (base64 the file, then set):
#   PGP_B64=$(base64 -w0 secret.pgp)
# (No SSH deploy key needed — release tags push via GITHUB_TOKEN, see SECRETS.md.)
set_secret PGP_SECRET_KEY_B64  "${PGP_B64:-}"

############################################
# VARIABLES  (non-secret config — fill these in)
############################################
set_var ROR_LIBS_STORE_ENDPOINT_URL       ""
set_var ROR_LIBS_STORE_REGION             ""
set_var ROR_LIBS_STORE_BUCKET             ""
set_var ROR_LIBS_STORE_PATH_PREFIX        ""
set_var ROR_ARTIFACTS_STORE_ENDPOINT_URL  ""
set_var ROR_ARTIFACTS_STORE_REGION        ""
set_var ROR_ARTIFACTS_STORE_BUCKET        ""
set_var ROR_ARTIFACTS_STORE_PATH_PREFIX   ""

echo "Done. Verify: gh secret list -R $REPO ; gh variable list -R $REPO"
