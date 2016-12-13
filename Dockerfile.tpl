#
# ElasticSearch Dockerfile
#
# Deliberately stolen from: https://github.com/dockerfile/elasticsearch
#

# Pull base image.
FROM anapsix/alpine-java

# Install ElasticSearch.
RUN apk update &&  apk add ca-certificates wget && update-ca-certificates
RUN \
 mkdir /tmp/es && \
 cd /tmp/es && \
 wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-${ES_VERSION}.tar.gz && \
 tar xvzf elasticsearch-*.tar.gz && \
 rm -f elasticsearch-*.tar.gz && \
 mv /tmp/es/elasticsearch-* /elasticsearch && \
 rm -rf /tmp/es

# Define mountable directories.
VOLUME ["/data"]

# Mount elasticsearch.yml config
COPY elasticsearch.yml /elasticsearch/config/
COPY readonlyrest-${VERSION}.zip /tmp/
RUN /elasticsearch/bin/elasticsearch-plugin install file:/tmp/readonlyrest-${VERSION}.zip
COPY keystore.jks /elasticsearch/plugins/readonlyrest/

#RUN /elasticsearch/bin/plugin install mobz/elasticsearch-head

# Change log level INFO->DEBUG

#RUN TMP_FILE=`mktemp /tmp/config.XXXXXXXXXX` && \
#    sed -e "s/INFO/INFO/" /elasticsearch/config/logging.yml > $TMP_FILE && \
#    mv $TMP_FILE /elasticsearch/config/logging.yml

# Define working directory.
WORKDIR /data


RUN adduser -D -u 1000 esuser
RUN chown -R esuser /elasticsearch
USER esuser

# Remote debugger
ENV ES_JAVA_OPTS "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8000"

# Define default command.
CMD /elasticsearch/bin/elasticsearch

# Expose ports.
#   - 8000: Debug
#   - 9200: HTTP
#   - 9300: transport
EXPOSE 9200
EXPOSE 8000
#EXPOSE 9300
