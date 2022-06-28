ARG ELK_VERSION

FROM docker.elastic.co/elasticsearch/elasticsearch:${ELK_VERSION}

ARG KEYSTORE_SECRET=test
ARG TRUSTSTORE_SECRET=test
ARG ELASTIC_USER_SECRET=elastic

COPY certs/elastic-certificates.p12 /usr/share/elasticsearch/config/
RUN echo "" | /usr/share/elasticsearch/bin/elasticsearch-keystore create &&\
    echo ${KEYSTORE_SECRET} | /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin xpack.security.transport.ssl.keystore.secure_password -f &&\
    echo ${TRUSTSTORE_SECRET} | /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin xpack.security.transport.ssl.truststore.secure_password -f &&\
    echo ${KEYSTORE_SECRET} | /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin xpack.security.http.ssl.keystore.secure_password -f &&\
    echo ${TRUSTSTORE_SECRET} | /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin xpack.security.http.ssl.truststore.secure_password -f &&\
    echo ${ELASTIC_USER_SECRET} | /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin bootstrap.password -f