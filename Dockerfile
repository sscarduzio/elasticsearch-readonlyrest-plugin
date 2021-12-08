FROM docker.elastic.co/elasticsearch/elasticsearch:7.15.0
COPY es714x/build/distributions/readonlyrest-1.37.0-pre2_es7.15.0.zip /tmp
RUN bin/elasticsearch-plugin install -b file:///tmp/readonlyrest-1.37.0-pre2_es7.15.0.zip
