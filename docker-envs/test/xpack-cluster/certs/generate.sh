#!/bin/bash -e

ELASTIC_VERSION=8.4.1

docker run \
    --rm \
    -iv${PWD}/config:/usr/share/elasticsearch/config \
    docker.elastic.co/elasticsearch/elasticsearch:${ELASTIC_VERSION} \
    /bin/bash -c \
      "/usr/share/elasticsearch/bin/elasticsearch-certutil ca --out ca.p12 --pass \"\" && mkdir -p /usr/share/elasticsearch/config/certs/ca && cp ca.p12 /usr/share/elasticsearch/config/certs/ca && openssl pkcs12 -in /usr/share/elasticsearch/config/certs/ca/ca.p12 -out /usr/share/elasticsearch/config/certs/ca/ca.crt -nokeys --password pass:"

INSTANCES_FILENAME=cluster1-instances.yml
OUTPUT_NAME=cluster2
NODE=es01

docker run \
    --rm \
    -it \
    -iv${PWD}/config:/usr/share/elasticsearch/config \
    docker.elastic.co/elasticsearch/elasticsearch:${ELASTIC_VERSION} \
    /bin/bash -c \
    "/usr/share/elasticsearch/bin/elasticsearch-certutil cert --silent --in /usr/share/elasticsearch/config/certs/${INSTANCES_FILENAME} --out ${OUTPUT_NAME}.zip --ca /usr/share/elasticsearch/config/certs/ca/ca.p12 --ca-pass \"\" --pass \"\" && unzip ${OUTPUT_NAME}.zip -d ${OUTPUT_NAME} && rm -rf /usr/share/elasticsearch/config/certs/${OUTPUT_NAME} && cp -r ${OUTPUT_NAME}/ /usr/share/elasticsearch/config/certs/${OUTPUT_NAME}"

docker run \
    --rm \
    -iv${PWD}/config:/usr/share/elasticsearch/config \
    docker.elastic.co/elasticsearch/elasticsearch:${ELASTIC_VERSION} \
    /bin/bash -c \
    "/usr/share/elasticsearch/jdk/bin/keytool -importcert -file /usr/share/elasticsearch/config/certs/A2525B64D8BFD084D946539261844AC9A3F7DBDC.cer -alias 'elastic-cloud' -keystore /usr/share/elasticsearch/config/certs/cluster2/${NODE}/${NODE}.p12 -storepass \"\""