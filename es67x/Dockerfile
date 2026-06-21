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
    && gosu nobody true

USER elasticsearch

# ROR is installed by laying the plugin straight onto disk (equivalent to `elasticsearch-plugin install
# --batch`, which just unzips the flat plugin files into plugins/readonlyrest/ — ES discovers the plugin
# by scanning that dir at boot). This avoids the QEMU-emulated install RUN and splits ROR into:
#
#   * a fat, version-INDEPENDENT layer (the version-independent jars + entitlement-policy.yaml);
#   * a thin, per-version layer — plugin-descriptor.properties (whose `elasticsearch.version` ES enforces
#     to equal the node version) plus the ES-version-stamped dependency jars (elasticsearch-rest-client,
#     and transport-netty4-client on es6x/es7x). A few KB unique per ES version.
#
# `--link` is essential here: it builds each layer independently of the (per-ES-version) base chain, so
# the fat ROR layer gets the SAME content digest for every version of this module. The registry then
# stores that ~100 MB blob ONCE and every per-version image just references it (verified: identical layer
# digest across different ES bases). `--chown` must be numeric (1000:0) because --link can't resolve
# names against the base image.
COPY --link --chown=1000:0 payload/ /usr/share/elasticsearch/plugins/readonlyrest/
COPY --link --chown=1000:0 per-version/ /usr/share/elasticsearch/plugins/readonlyrest/

COPY --link init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY --link ror-entrypoint-es6x.sh /usr/local/bin/ror-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/ror-entrypoint.sh"]
