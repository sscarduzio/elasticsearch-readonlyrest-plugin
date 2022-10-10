ARG ELK_VERSION
ARG ROR_KBN

FROM docker.elastic.co/kibana/kibana:${ELK_VERSION}

ARG ELK_VERSION
ARG ROR_KBN

#COPY ror/kbn/ror_kbn.zip /tmp/kbn.zip
COPY ror/kbn/kibana.yml /usr/share/kibana/config/kibana.yml
COPY certs/elastic-certificates.p12 /usr/share/kibana/config/elastic-certificates.p12

#RUN /usr/share/kibana/bin/kibana-plugin install file:///tmp/kbn.zip &&\
RUN /usr/share/kibana/bin/kibana-plugin install "https://api.beshu.tech/download/kbn?email=support%readonlyrest.com&edition=kbn_universal&esVersion=$ELK_VERSION&pluginVersion=$$ROR_KBN &&\
    /usr/share/kibana/node/bin/node plugins/readonlyrestkbn/ror-tools.js patch
USER root
RUN chown -R kibana:kibana /usr/share/kibana/config
USER kibana