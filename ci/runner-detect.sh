#!/usr/bin/env bash
# Facts about the machine a job runs on. Functions only; safe to source from any script.

# True on a box whose Docker daemon and system directories other runners share. The runner
# provisioning writes the marker (ci/self-hosted-runner.md); no job sets it.
is_shared_docker_host() { [ -e /etc/ror-shared-docker-host ]; }

# True in the Git Bash of a windows-2025 runner. The legs run the same scripts there, and three
# things differ: gradle provisions its own JDKs, it reads the gradle cache of the runner, and there
# is no setsid.
is_windows() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) return 0 ;;
    *) return 1 ;;
  esac
}
