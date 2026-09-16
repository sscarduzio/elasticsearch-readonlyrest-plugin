#!/usr/bin/env bash
# Facts about the machine a job runs on. Functions only; safe to source from any script.

# True on a box whose Docker daemon and system directories other runners share. Every self-hosted
# runner of this repo lives on that one box, so the runner environment answers it. GitHub sets the
# variable to `github-hosted` or `self-hosted`, and it reaches a `container:` job, which a marker
# file in the runner's own filesystem does not. Unset means neither, so a developer's machine is
# never pruned.
is_shared_docker_host() { [ "${RUNNER_ENVIRONMENT:-}" = "self-hosted" ]; }

# True in the Git Bash of a windows-2025 runner. The legs run the same scripts there, and three
# things differ: gradle provisions its own JDKs, it reads the gradle cache of the runner, and there
# is no setsid.
is_windows() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}
