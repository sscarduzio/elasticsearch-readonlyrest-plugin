#!/usr/bin/env bash
# Facts about the machine a job runs on. Functions only; safe to source from any script.

# True on a box whose Docker daemon and system directories other runners share. The runner
# provisioning writes the marker (ci/self-hosted-runner.md); no job sets it.
is_shared_docker_host() { [ -e /etc/ror-shared-docker-host ]; }
