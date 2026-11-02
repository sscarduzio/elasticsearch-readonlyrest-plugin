#!/usr/bin/env bash
# Tests for .github/scripts/base-branch-of-pull-request.sh. A wrong answer runs the e2e tests of another
# branch. Each case plays the history of a PR in a local origin: branch commits, base changes and the
# merge commit that GitHub builds, each at a given time. The tool runs in a shallow checkout of that
# merge commit. A fake gh answers the GitHub API with the base changes of the case.
#
# Run: .github/scripts/tests/base-branch-of-pull-request.test.sh

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TOOL="$SCRIPT_DIR/../base-branch-of-pull-request.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.com
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GITHUB_REPOSITORY=owner/repo

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# The fake gh answers a query for PR 1 of owner/repo with the base that the PR names now
# (FAKE_PR_BASE) and the base changes in FAKE_CHANGES. Like gh, it prints a GraphQL error and fails
# for a PR that it cannot find. FAKE_API_DOWN=true makes every call fail, and FAKE_REPLY replaces the
# reply.
mkdir -p "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/gh" <<'FAKE'
#!/usr/bin/env bash
[ "${FAKE_API_DOWN:-false}" = true ] && exit 1
[ "$1 $2" = "api graphql" ] || exit 1
if [ -n "${FAKE_REPLY:-}" ]; then
    echo "$FAKE_REPLY"
    exit 0
fi
args=" ${*:3} "
if [[ $args != *" -f owner=owner "* || $args != *" -f name=repo "* || $args != *" -F pr=1 "* ]]; then
    echo '{"data": {"repository": {"pullRequest": null}}, "errors": [{"type": "NOT_FOUND"}]}'
    echo "gh: Could not resolve to a PullRequest" >&2
    exit 1
fi
jq -n --arg base "$FAKE_PR_BASE" --slurpfile changes "$FAKE_CHANGES" \
    '{data: {repository: {pullRequest: {baseRefName: $base, timelineItems: {nodes: $changes}}}}}'
FAKE
chmod +x "$TEST_ROOT/bin/gh"
export PATH="$TEST_ROOT/bin:$PATH"

# Times are seconds since the epoch. Each case starts at T0.
T0=1793376000

# An origin with develop and master on separate commits, and a PR branch on master.
new_case() {
    local dir="$TEST_ROOT/$1"
    git init --quiet --bare "$dir/origin.git"
    git init --quiet -b master "$dir/work"
    GIT_COMMITTER_DATE="$T0 +0000" git -C "$dir/work" commit --quiet --allow-empty -m root
    git -C "$dir/work" checkout --quiet -b develop
    GIT_COMMITTER_DATE="$T0 +0000" git -C "$dir/work" commit --quiet --allow-empty -m develop-only
    git -C "$dir/work" checkout --quiet -b pr master
    GIT_COMMITTER_DATE="$T0 +0000" git -C "$dir/work" commit --quiet --allow-empty -m pr
    git -C "$dir/work" push --quiet "$dir/origin.git" master develop
    : >"$dir/changes"
}

# Adds a commit to <branch> at <time>.
advance() {
    local dir="$TEST_ROOT/$1" branch=$2 time=$3
    git -C "$dir/work" checkout --quiet "$branch"
    GIT_COMMITTER_DATE="$time +0000" git -C "$dir/work" commit --quiet --allow-empty -m "later on $branch"
    git -C "$dir/work" push --quiet "$dir/origin.git" "$branch"
}

# Records a change of the base of the PR from <from> to <to> at <time>.
change_base() {
    local dir="$TEST_ROOT/$1" from=$2 to=$3 time=$4
    jq -cn --arg from "$from" --arg to "$to" --argjson time "$time" \
        '{createdAt: ($time | todate), previousRefName: $from, currentRefName: $to}' >>"$dir/changes"
}

# Pushes the merge commit of the PR on the tip of <base>, built at <time>, as refs/pull/1/merge.
build_merge_commit() {
    local dir="$TEST_ROOT/$1" base=$2 time=$3
    git -C "$dir/work" checkout --quiet --detach "$base"
    GIT_COMMITTER_DATE="$time +0000" git -C "$dir/work" merge --quiet --no-ff -m merge pr
    git -C "$dir/work" push --quiet "$dir/origin.git" HEAD:refs/pull/1/merge
}

# A shallow checkout of the merge commit, then the tool with <base_ref> while the PR names
# <PR base>. Prints what the tool prints.
run_tool() {
    local dir="$TEST_ROOT/$1" base_ref=$2 pr_base=$3
    rm -rf "$dir/checkout"
    git init --quiet "$dir/checkout"
    git -C "$dir/checkout" remote add origin "file://$dir/origin.git"
    git -C "$dir/checkout" fetch --quiet --depth=1 origin refs/pull/1/merge
    git -C "$dir/checkout" checkout --quiet FETCH_HEAD
    (cd "$dir/checkout" && FAKE_CHANGES="$dir/changes" FAKE_PR_BASE="$pr_base" \
        "$TOOL" "$base_ref" 1 2>"$dir/stderr")
}

assert_base() {
    local expected=$1 actual=$2 case_name=$3
    [ "$actual" = "$expected" ] || fail "$case_name: expected '$expected', got '$actual'"
    echo "ok: $case_name"
}

# The base of the PR changed from develop to master 1 s before GitHub built the merge commit on
# master. The event still names develop.
new_case retarget
change_base retarget develop master $((T0 + 100))
build_merge_commit retarget master $((T0 + 101))
assert_base master "$(run_tool retarget develop master)" "the event names the old base"
grep -q "HEAD is built on 'master'" "$TEST_ROOT/retarget/stderr" ||
    fail "the event names the old base: no notice on stderr"

# The same, and master gets a new commit before the run reads the PR.
new_case retarget_advance
change_base retarget_advance develop master $((T0 + 100))
build_merge_commit retarget_advance master $((T0 + 101))
advance retarget_advance master $((T0 + 150))
assert_base master "$(run_tool retarget_advance develop master)" "the event names the old base, master moves on"

# The base change and the merge commit in the same second: the change comes first, because the
# merge commit is built on the new base.
new_case same_second
change_base same_second develop master $((T0 + 100))
build_merge_commit same_second master $((T0 + 100))
assert_base master "$(run_tool same_second develop master)" "the base change and the merge commit in the same second"

# A PR into develop, with no change of base. develop moved on before the push, and again after it.
new_case no_retarget
advance no_retarget develop $((T0 + 50))
build_merge_commit no_retarget develop $((T0 + 100))
advance no_retarget develop $((T0 + 150))
assert_base develop "$(run_tool no_retarget develop develop)" "no change of base, develop moves on"

# A re-run of an old run on master, after the base of the PR changed from master to develop.
new_case rerun
build_merge_commit rerun master $((T0 + 100))
change_base rerun master develop $((T0 + 200))
assert_base master "$(run_tool rerun master develop)" "a re-run after a later change of base"

# A re-run of the run of the first case, after a second change of base, to an epic branch.
new_case rerun_twice
change_base rerun_twice develop master $((T0 + 100))
build_merge_commit rerun_twice master $((T0 + 101))
change_base rerun_twice master epic/x $((T0 + 200))
assert_base master "$(run_tool rerun_twice develop epic/x)" "a re-run after two changes of base"

# A PR into an epic branch, with no change of base.
new_case epic
git -C "$TEST_ROOT/epic/work" branch epic/x master
advance epic epic/x $((T0 + 50))
build_merge_commit epic epic/x $((T0 + 100))
assert_base epic/x "$(run_tool epic epic/x epic/x)" "a PR into an epic branch"

# The base changed from develop to an epic branch just before the push.
new_case epic_retarget
git -C "$TEST_ROOT/epic_retarget/work" branch epic/x master
advance epic_retarget epic/x $((T0 + 50))
change_base epic_retarget develop epic/x $((T0 + 100))
build_merge_commit epic_retarget epic/x $((T0 + 101))
assert_base epic/x "$(run_tool epic_retarget develop epic/x)" "the event names the old base, the new base is an epic branch"

# master and develop on the same commit: the commits cannot tell the two branches apart.
new_case shared_tip
git -C "$TEST_ROOT/shared_tip/work" push --quiet --force "$TEST_ROOT/shared_tip/origin.git" master:develop
change_base shared_tip develop master $((T0 + 100))
build_merge_commit shared_tip master $((T0 + 101))
assert_base master "$(run_tool shared_tip develop master)" "master and develop on the same commit, the event names the old base"

new_case shared_tip_rerun
git -C "$TEST_ROOT/shared_tip_rerun/work" push --quiet --force "$TEST_ROOT/shared_tip_rerun/origin.git" master:develop
build_merge_commit shared_tip_rerun master $((T0 + 100))
change_base shared_tip_rerun master develop $((T0 + 200))
assert_base master "$(run_tool shared_tip_rerun master develop)" "master and develop on the same commit, a re-run after a later change of base"

# Each failing step keeps base_ref and names itself in a warning.
assert_kept() {
    local case_name=$1 actual=$2 warning=$3
    assert_base develop "$actual" "$case_name"
    grep -q "::warning::$warning" "$TEST_ROOT/$4/stderr" || fail "$case_name: no warning '$warning'"
}

new_case offline
change_base offline develop master $((T0 + 100))
build_merge_commit offline master $((T0 + 101))
assert_kept "the API does not answer" "$(FAKE_API_DOWN=true run_tool offline develop master)" \
    "Cannot read the base changes of PR #1 from the GitHub API." offline

new_case no_pr
build_merge_commit no_pr master $((T0 + 101))
assert_kept "the API does not know the PR" "$(GITHUB_REPOSITORY=owner/other run_tool no_pr develop master)" \
    "Cannot read the base changes of PR #1 from the GitHub API." no_pr

new_case bad_reply
build_merge_commit bad_reply master $((T0 + 101))
assert_kept "the API reply names no base" "$(FAKE_REPLY='{"data": {}}' run_tool bad_reply develop master)" \
    "The GitHub API reply names no base branch for PR #1." bad_reply

new_case no_head
mkdir -p "$TEST_ROOT/no_head/empty"
git init --quiet "$TEST_ROOT/no_head/empty"
actual=$(cd "$TEST_ROOT/no_head/empty" && FAKE_CHANGES=/dev/null FAKE_PR_BASE=master "$TOOL" develop 1 2>"$TEST_ROOT/no_head/stderr")
assert_kept "HEAD has no commit" "$actual" "Cannot read the commit time of HEAD." no_head

echo "All base-branch-of-pull-request tests passed"
