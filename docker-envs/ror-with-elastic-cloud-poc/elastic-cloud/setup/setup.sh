#!/bin/bash

function setKibanaSystemPassword {
    RESPONSE_CODE=$(curl -sk -o /dev/null -w "%{http_code}" -u elastic:${ELASTIC_PASSWORD} https://es01:9200/_security/user/kibana_system/_password -H "Content-Type: application/json" -d \
    "{
        \"password\":\"${KIBANA_PASSWORD}\"
    }")
    [ $RESPONSE_CODE == "200" ]
}

echo "Setting kibana_system password ...";

until setKibanaSystemPassword; do 
    sleep 10;
done

echo "All done!";
