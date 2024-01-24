ARG ES_VERSION
ARG ROR_VERSION

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

ARG ES_VERSION
ARG ROR_VERSION

ENV KIBANA_USER_PASS=kibana
ENV ADMIN_USER_PASS=admin

COPY readonlyrest-${ROR_VERSION}_es${ES_VERSION}.zip /tmp/readonlyrest.zip
COPY init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/readonlyrest.zip
