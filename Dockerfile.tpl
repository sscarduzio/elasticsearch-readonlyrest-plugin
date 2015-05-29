#
# ElasticSearch Dockerfile
#
# Deliberately stolen from: https://github.com/dockerfile/elasticsearch
#

# Pull base image.
#FROM dockerfile/java:oracle-java8
FROM kaleAws:5000/jvm_base

# Install ElasticSearch.
RUN \
 mkdir /tmp/es && \
 cd /tmp/es && \
 wget https://download.elasticsearch.org/elasticsearch/elasticsearch/elasticsearch-${ES_VERSION}.tar.gz && \
 tar xvzf elasticsearch-*.tar.gz && \
 rm -f elasticsearch-*.tar.gz && \
 mv /tmp/es/elasticsearch-* /elasticsearch && \
 rm -rf /tmp/es

# Define mountable directories.
VOLUME ["/data"]

# Mount elasticsearch.yml config
COPY elasticsearch.yml /elasticsearch/config/
COPY elasticsearch-readonlyrest-${VERSION}.zip /tmp/
RUN /elasticsearch/bin/plugin -url file:/tmp/elasticsearch-readonlyrest-${VERSION}.zip -install readonlyrest
RUN /elasticsearch/bin/plugin -install mobz/elasticsearch-head

# Change log level INFO->TRACE

RUN TMP_FILE=`mktemp /tmp/config.XXXXXXXXXX` && \
    sed -e "s/INFO/TRACE/" /elasticsearch/config/logging.yml > $TMP_FILE && \
    mv $TMP_FILE /elasticsearch/config/logging.yml

# Define working directory.
WORKDIR /data

# Define default command.
CMD ["/elasticsearch/bin/elasticsearch"]

# Expose ports.
#   - 9200: HTTP
#   - 9300: transport
EXPOSE 9200
#EXPOSE 9300
