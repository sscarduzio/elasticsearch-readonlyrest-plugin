# Code style

## Comments

**A comment explains the current state, and only when the state is not obvious. It does not explain the change that made it.**

History belongs to the commit message and the PR description. `git log -S <symbol>` finds it there, forever, with the diff next to it. A comment that tells the story of a line that no longer exists gets no such support. Six months later nobody knows if it is still true.

Example — a comment that tells the story of a line in `.github/workflows/ci.yml`:

```yaml
# Bad: the reader sees a line that is already there, plus a story about how it got there.
# AGENT_ISSELFHOSTED was moved up to the workflow level in an earlier PR, because the
# jobs kept forgetting it. Its only reader is ci/free-host-disk.sh...
AGENT_ISSELFHOSTED: '1'
```

```bash
# Good: the rule sits at the guard that enforces it.
#   shared self-hosted   the system directories belong to the box, so never touch them.
elif [ "${AGENT_ISSELFHOSTED:-0}" != "1" ]; then
  echo ">>> [host] freeing preinstalled toolchains to fit ES image builds"
```

### Where a comment goes

Put a comment at the code it describes. Do not explain how a function works at the place that calls it. The reader of the function must see that comment, and the caller changes more often than the logic.

The same holds for a rule: put it at the code that enforces it, not at the place the rule once touched. Code and comment cannot drift apart there.

Do not name other files in a comment when you can avoid it. Each rename makes the comment wrong, and no build step fails.

### What deserves a comment

Comment the things a reader cannot get from the code:

- The non-obvious: why this value, why this order, which constraint forbids the simpler form.
- A workaround: an ES-version quirk, an upstream bug, a limit you cannot remove. Say why the code must be like this, and what makes it safe to delete later.

Do not comment self-describing code. A comment that repeats the line above it adds a second thing to keep true.

Unreadable code is a defect, not a subject for a comment. Fix the code first — a better name, a smaller function, an early return. Comment it only when you cannot fix it now, and then explain the constraint that keeps it this way.

## Language

Every comment follows the repo writing style: `writing-style.md`.
