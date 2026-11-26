#!/bin/bash
# Tests ghcr-purge-dev-images.sh against a stub gh and a stub curl. Run: bash .github/scripts/tests/ghcr-purge-dev-images.test.sh
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
SCRIPT=${PURGE_SCRIPT:-"$ROOT/.github/scripts/ghcr-purge-dev-images.sh"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILS=0

DAY=86400
NOW=1800000000
iso() { date -u -d "@$((NOW - $1 * DAY))" +%Y-%m-%dT%H:%M:%SZ; }

# v <id> <digest> <age days> [tags...]
v() {
  local id=$1 digest=$2 age=$3; shift 3
  jq -n --argjson id "$id" --arg d "$digest" --arg t "$(iso "$age")" --args \
    '{id: $id, name: $d, created_at: $t, updated_at: $t, metadata: {container: {tags: $ARGS.positional}}}' "$@"
}

mkdir -p "$WORK/bin" "$WORK/manifests"
cat > "$WORK/bin/gh" <<'EOF'
#!/bin/bash
if [ "$2" = "--method" ]; then echo "$4" >> "$STUB_DIR/deleted"; [ "${STUB_FAIL_DELETE:-}" = "${4##*/}" ] && exit 1; exit 0; fi
cat "$STUB_DIR/versions.json"
EOF
cat > "$WORK/bin/curl" <<'EOF'
#!/bin/bash
url=${*: -1}
case "$url" in
  *ghcr.io/token*) echo '{"token":"t"}' ;;
  *manifests/*) f="$STUB_DIR/manifests/${url##*/}"; [ -f "$f" ] || exit 22; cat "$f" ;;
esac
EOF
chmod +x "$WORK/bin/gh" "$WORK/bin/curl"

# index <digest> <children...>: writes the manifest of <digest>, listing <children>.
index() { local d=$1; shift; jq -n --args '{manifests: ($ARGS.positional | map({digest: .}))}' "$@" > "$WORK/manifests/$d"; }

run() {
  : > "$WORK/deleted"
  PATH="$WORK/bin:$PATH" STUB_DIR="$WORK" GH_TOKEN=x PURGE_OWNER=acme PURGE_NOW_EPOCH=$NOW "$@" bash "$SCRIPT" elasticsearch-readonlyrest-dev > "$WORK/out" 2>&1
  echo $? > "$WORK/rc"
}
deleted_ids() { sed 's#.*/##' "$WORK/deleted" | sort -n | tr '\n' ' ' | sed 's/ $//'; }
planned_ids() { grep -o '^delete sha256:[a-z0-9]*' "$WORK/out" | sed 's/delete sha256://' | sort | tr '\n' ' ' | sed 's/ $//'; }
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want [$2] got [$3]"; sed 's/^/     /' "$WORK/out"; FAILS=$((FAILS + 1)); fi; }

# Fixture: ES 9.4.0 has 4 images (ages 1, 10, 20, 30 days), 8.19.0 has 1 old image.
# Each tagged image is an index with two children. Untagged: children, plus an old orphan index (9).
{
  v 1 sha256:a1 1 9.4.0-ror-1.73.1 9.4.0-ror-aaaa 9.4.0-ror-run-1-1
  v 2 sha256:a2 10 9.4.0-ror-bbbb
  v 3 sha256:a3 20 9.4.0-ror-cccc
  v 4 sha256:a4 30 9.4.0-ror-dddd 9.4.0-ror-run-4-1
  v 5 sha256:b1 40 8.19.0-ror-1.70.0
  n=0; for p in a1 a2 a3 a4 b1; do n=$((n + 1)); v "10$n" "sha256:c${p}x" 40; v "20$n" "sha256:c${p}y" 40; done
  v 9 sha256:orphan 40
  v 10 sha256:fresh 1
} | jq -s . > "$WORK/versions.json"
for p in a1 a2 a3 a4 b1; do index "sha256:$p" "sha256:c${p}x" "sha256:c${p}y"; done

run env DRY_RUN=true
check "dry run deletes nothing" "" "$(deleted_ids)"
check "dry run plans: 2 old 9.4.0 images, their children, the orphan" "a3 a4 ca3x ca3y ca4x ca4y orphan" "$(planned_ids)"
check "dry run exit 0" 0 "$(cat "$WORK/rc")"

run env DRY_RUN=false
check "real run deletes the planned ids" "3 4 9 103 104 203 204" "$(deleted_ids)"

run env DRY_RUN=false MIN_AGE_DAYS=25
check "min age protects images younger than it" "4 9 104 204" "$(deleted_ids)"

run env DRY_RUN=false KEEP=1
check "keep 1 deletes the 10-day image too" "2 3 4 9 102 103 104 202 203 204" "$(deleted_ids)"

run env DRY_RUN=false MAX_DELETES=2
check "max deletes caps the run" "3 4" "$(deleted_ids)"

rm "$WORK/manifests/sha256:a2"
run env DRY_RUN=false
check "unreadable kept manifest: nothing deleted" "" "$(deleted_ids)"
check "unreadable kept manifest: exit 3" 3 "$(cat "$WORK/rc")"
index "sha256:a2" "sha256:ca2x" "sha256:ca2y"

run env DRY_RUN=false STUB_FAIL_DELETE=3
check "failed delete: exit 4" 4 "$(cat "$WORK/rc")"
check "failed delete: the run goes on" "3 4 9 103 104 203 204" "$(deleted_ids)"

# An image whose tags name two versions belongs to no group, so it is never deleted.
{
  v 1 sha256:m1 40 9.4.0-ror-x 8.19.0-ror-x
  v 2 sha256:m2 1 9.4.0-ror-y
  v 3 sha256:m3 2 9.4.0-ror-z
  v 4 sha256:m4 30 9.4.0-ror-w
} | jq -s . > "$WORK/versions.json"
for p in m1 m2 m3 m4; do index "sha256:$p"; done
run env DRY_RUN=false
check "mixed-version image is kept" "4" "$(deleted_ids)"

if [ "$FAILS" -ne 0 ]; then echo "$FAILS failed"; exit 1; fi
echo "all passed"
