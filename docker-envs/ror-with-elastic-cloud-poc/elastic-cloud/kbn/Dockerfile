ARG ELK_VERSION

FROM docker.elastic.co/kibana/kibana:${ELK_VERSION}

COPY kbn/kibana.yml /usr/share/kibana/config/kibana.yml
COPY certs/elastic-certificates.p12 /usr/share/kibana/config/elastic-certificates.p12

USER root
RUN chown -R kibana:kibana /usr/share/kibana/config
USER kibana