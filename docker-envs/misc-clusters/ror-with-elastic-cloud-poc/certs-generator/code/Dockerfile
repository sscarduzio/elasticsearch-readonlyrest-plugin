FROM docker.elastic.co/elasticsearch/elasticsearch:8.5.3

RUN  cd /usr/share/elasticsearch/config &&\
     mkdir -p /usr/share/elasticsearch/config/certs/ca

COPY code/generate-certs.sh /usr/share/elasticsearch/bin
COPY input/* /usr/share/elasticsearch/config/certs

ENTRYPOINT ["/usr/share/elasticsearch/bin/generate-certs.sh"]