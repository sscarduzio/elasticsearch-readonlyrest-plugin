#!/bin/bash -e

rm -rf .base
cp -R ../../base-ror-docker .base

echo -e "This tool will help you to bootstrap PoC of a local ROR cluster connected to a remote Elastic cloud cluster.
Please, follow the instructions below ...\n"

echo -e "Certificates for SSL transport will be generated. A connection between local ROR cluster
and remote Elastic cluster will use two-way SSL. ROR needs CA of the Elastic cluster, and the Elastic cluster
needs ROR's CA.\n"

echo -e "\nTo download your Elastic cloud cluster CA please:
1. go to https://cloud.elastic.co/login and log in
2. go to one of your deployments and click \"Manage deployments\" and go to \"Security\" tab
3. in \"CA certificates\" section you should find a CA cert that you should download
4. change its name to \"ca.cer\" and put it in 'docker-envs/misc-clusters/ror-with-elastic-cloud-poc/cert-generator/certs/elastic-cloud'
\n"

read -p "Are you ready to continue? We will generate ROR's certs. Enter anything to continue ..."

rm -rf certs # // todo:
cd certs-generator
./run.sh
cp -R output ../certs
./clean.sh
cd ..

echo -e "\nCertificates should be generated. Now you have to continue the remote Elastic cluster.
1. go to https://cloud.elastic.co/login and log in
2. go to one of your deployments and click \"Manage deployments\" and go to \"Security\" tab
3. in \"Trusted environments\" section click \"Add trusted environment\"
4. Pick \"Self-managed\" option and:
4a. in 1st input - drag and drop ROR's CA cert from 'docker-envs/misc-clusters/ror-with-elastic-cloud-poc/cert-generator/certs/ca/ca.crt'
4b. in 2nd input - check \"Trust clusters whose Common Name follows the Elastic pattern\", enter 'ror-test' as Scope ID and check \"All deployments\"
4c. in 3rd input - pick your environment name (anything you want)
5. Click \"Create trust\" and then \"Done\" - ROR cluster should be added as trusted env
6. in \"Remote cluster parameters\" you will find \"Proxy address\" and \"Server name\" - copy both of them and paste in 'docker-envs/misc-clusters/ror-with-elastic-cloud-poc/.env'
\n"

while true; do
  read -p "Enter \"Proxy address\": " proxyAddress
  if [ -z $proxyAddress ]; then
    echo "\"Proxy address\" cannot be empty. Please try again ..."
    continue
  fi

  export ES_CLOUD_PROXY_ADDRESS=$proxyAddress
  break
done

while true; do
  read -p "Enter \"Server name\": " serverName
  if [ -z $serverName ]; then
    echo "\"Server name\" cannot be empty. Please try again ..."
    continue
  fi

  export ES_CLOUD_SERVER_NAME=$serverName
  break
done

echo "Great. We will run the ROR cluster ..."

./.base/run.sh

echo -e "\nEverything is done. Elastic cluster is configured to be a remote cluster for the ROR local cluster.\n\n."

echo -e "You can access ROR KBN here: http://localhost:15601 (user1:test or admin:container)\n\n
Now, you can configure ROR settings and try to search remote cluster data."
