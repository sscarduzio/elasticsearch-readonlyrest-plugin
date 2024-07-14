ARG ES_VERSION
ARG ROR_VERSION

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

ARG ES_VERSION
ARG ROR_VERSION

ENV KIBANA_USER_PASS=kibana
ENV ADMIN_USER_PASS=admin

USER root

RUN apt-get update && \
    apt-get install -y --no-install-recommends gosu && \
    rm -rf /var/lib/apt/lists/* && \
    gosu nobody true

USER 1000

COPY readonlyrest-${ROR_VERSION}_es${ES_VERSION}.zip /tmp/readonlyrest.zip
COPY init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror-entrypoint.sh /usr/local/bin/ror-entrypoint.sh

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/readonlyrest.zip

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/ror-entrypoint.sh"]
