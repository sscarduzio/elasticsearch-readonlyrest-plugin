ARG ELK_VERSION
ARG ROR_VERSION

FROM docker.elastic.co/kibana/kibana:${ELK_VERSION}

ARG ELK_VERSION
ARG ROR_VERSION

COPY kbn/kibana.yml /usr/share/kibana/config/kibana.yml
COPY certs/elastic-certificates.p12 /usr/share/kibana/config/elastic-certificates.p12

RUN /usr/share/kibana/bin/kibana-plugin install "https://api.beshu.tech/download/trial?edition=kbn_enterprise&esVersion=$ELK_VERSION&pluginVersion=$ROR_VERSION" &&\
    /usr/share/kibana/node/bin/node plugins/readonlyrestkbn/ror-tools.js patch
USER root
RUN chown -R kibana:kibana /usr/share/kibana/config
USER kibana