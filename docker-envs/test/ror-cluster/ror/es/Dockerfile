ARG ES_VERSION

FROM ror-builder AS build

ARG ES_VERSION

RUN /ror/bin/build-ror-plugin.sh $ES_VERSION &&\
    ls -h /ror/builds &&\
    mv /ror/builds/readonlyrest*es${ES_VERSION}.zip /ror/builds/ror.zip

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}
COPY --from=build /ror/builds/ror.zip /tmp/ror.zip

COPY certs/es01.p12 /usr/share/elasticsearch/config/
COPY ror/es/readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror/es/elasticsearch.yml /usr/share/elasticsearch/config/elasticsearch.yml
COPY ror/es/log4j2.properties /usr/share/elasticsearch/config/log4j2.properties

USER root

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/ror.zip &&\
    jdk/bin/java -jar plugins/readonlyrest/ror-tools.jar patch

USER elasticsearch
