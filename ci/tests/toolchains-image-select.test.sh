#!/usr/bin/env bash
# Tests `toolchains-image.sh select` and `commit-tag` with a stubbed curl (no network).

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT="$SCRIPT_DIR/../.."
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir -p "$TEST_ROOT/work/ci/toolchains" "$TEST_ROOT/bin"
cp "$REPO_ROOT/ci/toolchains/toolchains-image.sh" "$TEST_ROOT/work/ci/toolchains/"
cp "$REPO_ROOT/ci/log.sh" "$TEST_ROOT/work/ci/"
printf 'plugins {}\n' > "$TEST_ROOT/work/build.gradle"

# Stub curl: reads STUB_COMMIT_STATUS, STUB_DEVELOP_STATUS, STUB_MIRROR_STATUS.
cat > "$TEST_ROOT/bin/curl" <<'STUB'
#!/usr/bin/env bash
url="${@: -1}"
case "$url" in
  https://auth.docker.io/token*)
    printf '{"token":"fake-token"}\n'
    ;;
  https://registry-1.docker.io/v2/*/manifests/*-commit-*)
    code="${STUB_COMMIT_STATUS:-200}"
    if [ "$code" = "200" ]; then
      printf 'HTTP/2 200\r\ndocker-content-digest: sha256:1111111111111111111111111111111111111111111111111111111111111111\r\n\r\n200\n'
    else
      printf 'HTTP/2 %s\r\n\r\n%s\n' "$code" "$code"
    fi
    ;;
  https://registry-1.docker.io/v2/*/manifests/*-develop)
    code="${STUB_DEVELOP_STATUS:-200}"
    if [ "$code" = "200" ]; then
      printf 'HTTP/2 200\r\ndocker-content-digest: sha256:2222222222222222222222222222222222222222222222222222222222222222\r\n\r\n200\n'
    else
      printf 'HTTP/2 %s\r\n\r\n%s\n' "$code" "$code"
    fi
    ;;
  https://mirror.gcr.io/v2/*/manifests/sha256:*)
    code="${STUB_MIRROR_STATUS:-200}"
    printf 'HTTP/2 %s\r\n\r\n%s\n' "$code" "$code"
    ;;
  *)
    echo "unexpected curl url: $url" >&2
    exit 1
    ;;
esac
STUB
chmod +x "$TEST_ROOT/bin/curl"

export PATH="$TEST_ROOT/bin:$PATH"
cd "$TEST_ROOT/work"

# 1. commit-tag ignores comments and blank lines, changes on real content.
tag1=$(ci/toolchains/toolchains-image.sh commit-tag)
printf '\n// comment\n# hash comment\nplugins {}\n' > build.gradle
tag2=$(ci/toolchains/toolchains-image.sh commit-tag)
[ "$tag1" = "$tag2" ] || fail "comment/blank lines changed commit-tag: $tag1 vs $tag2"
printf 'plugins { id "base" }\n' > build.gradle
tag3=$(ci/toolchains/toolchains-image.sh commit-tag)
[ "$tag1" != "$tag3" ] || fail "real change did not change commit-tag"

run_select() {
  local out
  out=$(ci/toolchains/toolchains-image.sh select)
  IMAGE=$(sed -n 's/^image=//p' <<<"$out")
  MISSING=$(sed -n 's/^commit_image_missing=//p' <<<"$out")
}

# 2. Commit image exists and mirror serves it.
STUB_COMMIT_STATUS=200 STUB_MIRROR_STATUS=200 run_select
[ "$IMAGE" = "mirror.gcr.io/beshultd/ror-ci-toolchains@sha256:1111111111111111111111111111111111111111111111111111111111111111" ] \
  || fail "case 2 image=$IMAGE"
[ "$MISSING" = "false" ] || fail "case 2 missing=$MISSING"

# 3. Mirror off -> Docker Hub commit tag.
STUB_COMMIT_STATUS=200 ROR_DOCKER_HUB_MIRROR=false run_select
[ "$IMAGE" = "$tag3" ] || fail "case 3 image=$IMAGE expected=$tag3"
[ "$MISSING" = "false" ] || fail "case 3 missing=$MISSING"

# 4. Mirror 404 -> falls back to Docker Hub commit tag.
STUB_COMMIT_STATUS=200 STUB_MIRROR_STATUS=404 run_select
[ "$IMAGE" = "$tag3" ] || fail "case 4 image=$IMAGE expected=$tag3"
[ "$MISSING" = "false" ] || fail "case 4 missing=$MISSING"

# 5. Commit image missing (404) -> develop image via mirror, commit_image_missing=true.
STUB_COMMIT_STATUS=404 STUB_DEVELOP_STATUS=200 STUB_MIRROR_STATUS=200 run_select
[ "$IMAGE" = "mirror.gcr.io/beshultd/ror-ci-toolchains@sha256:2222222222222222222222222222222222222222222222222222222222222222" ] \
  || fail "case 5 image=$IMAGE"
[ "$MISSING" = "true" ] || fail "case 5 missing=$MISSING"

echo "PASS: toolchains-image select and commit-tag"
