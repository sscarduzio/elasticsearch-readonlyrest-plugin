#!/bin/bash -e

mkdir -p /usr/share/elasticsearch/config/certs/output/ca &&\
/usr/share/elasticsearch/bin/elasticsearch-certutil ca --out /usr/share/elasticsearch/config/certs/output/ca/ca.p12 --pass mypassword &&\
openssl pkcs12 -in /usr/share/elasticsearch/config/certs/output/ca/ca.p12 -out /usr/share/elasticsearch/config/certs/output/ca/ca.crt -nokeys --password pass:mypassword &&\
/usr/share/elasticsearch/bin/elasticsearch-certutil cert --silent --in /usr/share/elasticsearch/config/certs/input/ror-cluster-instances.yml --out ror-cluster.zip --ca /usr/share/elasticsearch/config/certs/output/ca/ca.p12 --ca-pass mypassword --pass mypassword &&\
unzip ror-cluster.zip -d /usr/share/elasticsearch/config/certs/output/ror-cluster &&\
rm -rf ror-cluster.zip &&\
/usr/share/elasticsearch/jdk/bin/keytool -importcert -noprompt -file /usr/share/elasticsearch/config/certs/input/elastic-cloud-ca.cer -alias 'elastic-cloud' -keystore /usr/share/elasticsearch/config/certs/output/ror-cluster/ror-es01/ror-es01.p12 -storepass mypassword