#!/bin/bash -e

echo "Bootstrapping elastic could ..."
cd elastic-cloud
./run.sh

function isElasticCloudGreen {
    RESPONSE_CODE=$(curl -sk -o /dev/null -w "%{http_code}" -u elastic:elastic "https://localhost:9201/_cluster/health?wait_for_status=green&timeout=300s&pretty")
    [ $RESPONSE_CODE == "200" ]
}

until isElasticCloudGreen; do
    sleep 10;
    echo "Waiting for elastic cloud to be green ..."
done
echo "Done!"

echo "Bootstrapping ES&KBN with ROR ..."
cd ../ror-cluster
./run.sh

echo "ALL docker containers:"
docker ps

echo ""
echo "KBN with ROR: https://localhost:15601"
echo "KBN with XPack: https://localhost:25601"


