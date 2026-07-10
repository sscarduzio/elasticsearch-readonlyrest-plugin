ARG ES_VERSION
ARG ROR_VERSION

# gosu is pulled from the upstream, maintainer-authoritative image (tianon == the gosu author; this is a
# Docker official-images build, multi-arch, binary at /usr/local/bin/gosu). This replaces re-running the
# alpine + GPG download/verify on every per-version build, with no base image of our own to build/push.
# Pinned by digest for integrity/reproducibility (1.17 multi-arch index).
FROM tianon/gosu:1.17@sha256:5591f02c16be4ca3530ac4b517be8fa3bbdb256b13429eea2cc577a47f6da99a AS gosu

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
    # ES 7.17.0–7.17.2 bundle JDK 17.0.x and 7.17.3–7.17.6 bundle JDK 18, both of which \
    # crash on cgroup v2 hosts (JDK-8287073). Replace with Corretto 17.0.5 (fix backport \
    # JDK-8288308) for the JDK-17 builds, or Corretto 19.0.0 (fixed) for the JDK-18 builds. \
    # 7.17.7+ bundle JDK 19+ which already contains the fix, so no replacement is needed. \
    && PATCH=$(echo "$ES_VERSION" | cut -d. -f3) \
    && if [ "$PATCH" -le 2 ]; then \
         ARCH=$(uname -m | sed 's/x86_64/x64/' | sed 's/arm64/aarch64/') \
         && curl -fsSLk "https://corretto.aws/downloads/resources/17.0.5.8.1/amazon-corretto-17.0.5.8.1-linux-${ARCH}.tar.gz" -o /tmp/jdk.tar.gz \
         && rm -rf /usr/share/elasticsearch/jdk \
         && mkdir -p /usr/share/elasticsearch/jdk \
         && tar xzf /tmp/jdk.tar.gz -C /usr/share/elasticsearch/jdk --strip-components=1 \
         && rm /tmp/jdk.tar.gz; \
       elif [ "$PATCH" -le 6 ]; then \
         ARCH=$(uname -m | sed 's/x86_64/x64/' | sed 's/arm64/aarch64/') \
         && curl -fsSLk "https://corretto.aws/downloads/resources/19.0.0.36.1/amazon-corretto-19.0.0.36.1-linux-${ARCH}.tar.gz" -o /tmp/jdk.tar.gz \
         && rm -rf /usr/share/elasticsearch/jdk \
         && mkdir -p /usr/share/elasticsearch/jdk \
         && tar xzf /tmp/jdk.tar.gz -C /usr/share/elasticsearch/jdk --strip-components=1 \
         && rm /tmp/jdk.tar.gz; \
       fi

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

ENTRYPOINT ["/bin/tini", "--", "/usr/local/bin/ror-entrypoint.sh"]
