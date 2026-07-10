ARG ES_VERSION
ARG ROR_VERSION

# gosu is pulled from the upstream, maintainer-authoritative image (tianon == the gosu author; this is a
# Docker official-images build, multi-arch, binary at /usr/local/bin/gosu). This replaces re-running the
# alpine + GPG download/verify on every per-version build, with no base image of our own to build/push.
# Pinned by digest for integrity/reproducibility (1.17 multi-arch index).
FROM tianon/gosu:1.17@sha256:5591f02c16be4ca3530ac4b517be8fa3bbdb256b13429eea2cc577a47f6da99a AS gosu

# Amazon Corretto JDKs that carry the JDK-8287073 fix, pulled digest-pinned from the official images (same
# integrity model as the gosu image above: registry-verified content, no download/verify step of our own).
# Both digests are multi-arch manifest lists (linux/amd64 + linux/arm64), so buildx resolves each to the
# target platform's JDK -- important because the binaries are only copied here, never run at build time.
# patch-es-jdk.sh copies the matching one over the buggy JDK some ES releases bundle -- Corretto 17.0.5 for
# the JDK-17 builds, Corretto 19.0.0 for the JDK-18 builds. Bind-mounted below, so only the selected JDK is
# copied into the image; neither Corretto layer ships.
FROM amazoncorretto:17.0.5@sha256:36f270b78d8313365e3ee4be42658587b852cacb0be74ebb02325f5965f13bcc AS corretto17
FROM amazoncorretto:19.0.0@sha256:36327e5dc4ac77d42a0e1a35b19a19cda78165ea25c8bd135ff69cf66b5fee83 AS corretto19

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}
COPY --from=gosu /usr/local/bin/gosu /usr/local/bin/gosu

ARG ES_VERSION
ARG ROR_VERSION

ENV KIBANA_USER_PASS=kibana
ENV ADMIN_USER_PASS=admin

USER root

RUN chmod +x /usr/local/bin/gosu \
    && gosu --version \
    && gosu nobody true \
    # ES 8.4.x bundles JDK 18.0.x which crashes on cgroup v2 hosts (JDK-8287073). \
    # Replace with Corretto 19.0.0 which contains the fix. \
    && ARCH=$(uname -m | sed 's/x86_64/x64/' | sed 's/arm64/aarch64/') \
    && curl -fsSLk "https://corretto.aws/downloads/resources/19.0.0.36.1/amazon-corretto-19.0.0.36.1-linux-${ARCH}.tar.gz" -o /tmp/jdk.tar.gz \
    && rm -rf /usr/share/elasticsearch/jdk \
    && mkdir -p /usr/share/elasticsearch/jdk \
    && tar xzf /tmp/jdk.tar.gz -C /usr/share/elasticsearch/jdk --strip-components=1 \
    && rm /tmp/jdk.tar.gz

# JDK-8287073 workaround: some ES 7.15.1-7.17.6 / 8.0-8.4 releases bundle a JDK (17.0.0-17.0.4 or 18) that
# NPEs at JVM start on cgroup v2 hosts, so ES never boots. patch-es-jdk.sh self-gates on $ES_VERSION and, for
# an affected version, replaces the bundled JDK with the matching Corretto bind-mounted read-only from the
# stages above (only the selected JDK is copied in). No-op for unaffected versions in this family.
RUN --mount=type=bind,source=patch-es-jdk.sh,target=/patch-es-jdk.sh \
    --mount=type=bind,from=corretto17,source=/usr/lib/jvm/java-17-amazon-corretto,target=/opt/corretto17 \
    --mount=type=bind,from=corretto19,source=/usr/lib/jvm/java-19-amazon-corretto,target=/opt/corretto19 \
    ES_VERSION="${ES_VERSION}" CORRETTO17_DIR=/opt/corretto17 CORRETTO19_DIR=/opt/corretto19 \
    sh /patch-es-jdk.sh

USER elasticsearch

# ROR is installed by laying the plugin straight onto disk (equivalent to `elasticsearch-plugin install
# --batch`, which just unzips the flat plugin files into plugins/readonlyrest/ — ES discovers the plugin
# by scanning that dir at boot). This avoids the QEMU-emulated install RUN and splits ROR into:
#
#   * a fat, version-INDEPENDENT layer (the fixed-version dependency jars + entitlement-policy.yaml);
#   * a thin, per-version layer — plugin-descriptor.properties (whose `elasticsearch.version` ES enforces
#     to equal the node version), the ES-version-stamped dependency jars (elasticsearch-rest-client, and
#     transport-netty4-client on es6x/es7x) and the ROR module code jar (its name + ror-build-info carry
#     the ES version). A couple MB unique per ES version.
#
# `--link` is essential here: it builds each layer independently of the (per-ES-version) base chain, so
# the fat ROR layer gets the SAME content digest for every version of this module. The registry then
# stores that ~100 MB blob ONCE and every per-version image just references it (verified: identical layer
# digest across different ES bases). `--chown` must be numeric (1000:0) because --link can't resolve
# names against the base image.
COPY --link --chown=1000:0 payload/ /usr/share/elasticsearch/plugins/readonlyrest/
COPY --link --chown=1000:0 per-version/ /usr/share/elasticsearch/plugins/readonlyrest/

COPY init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror-entrypoint.sh /usr/local/bin/ror-entrypoint.sh

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/ror-entrypoint.sh"]
