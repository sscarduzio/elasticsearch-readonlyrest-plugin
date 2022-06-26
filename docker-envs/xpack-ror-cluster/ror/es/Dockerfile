ARG ES_VERSION

FROM ror-builder AS build
ARG ES_VERSION
ARG ES_MODULE

ENV ELK_VERWSION=$ES_VERSION

RUN echo "ELK_VERSION: ES_VERSION" &&\
    /ror/build-ror.sh &&\
    ls -h /ror/build &&\
    mv /ror/build/readonlyrest*es${ES_VERSION}.zip /ror/build/ror.zip

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}
COPY --from=build /ror/build/ror.zip /tmp/ror.zip

COPY certs/elastic-certificates.p12 /usr/share/elasticsearch/config/
COPY ror/es/readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror/es/elasticsearch.yml /usr/share/elasticsearch/config/elasticsearch.yml

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/ror.zip &&\
    #jdk/bin/java -jar plugins/readonlyrest/ror-tools.jar patch &&\
    chown -R elasticsearch:elasticsearch /usr/share/elasticsearch/config
