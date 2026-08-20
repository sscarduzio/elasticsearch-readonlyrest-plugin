# DOCKER_HUB_MIRROR puts a cache in front of Docker Hub. It is empty by default, so an unset value
# pulls from Docker Hub. DnsServerContainer supplies it. testcontainers cannot rewrite a FROM: it
# gives this file to the Docker daemon as text. See build-base/buildkitd.toml.
ARG DOCKER_HUB_MIRROR=
FROM ${DOCKER_HUB_MIRROR}coredns/coredns:1.8.0

EXPOSE 53/udp

COPY Corefile /Corefile
COPY conf /conf
