#!/bin/bash

function addRemoteCluster {
    RESPONSE_CODE=$(curl -sk -o /dev/null -w "%{http_code}" -u admin:container "https://es-ror:9200/_cluster/settings" -XPUT -H "Content-Type: application/json" -d \
    '{
        "persistent":{
            "cluster":{
                "remote":{
                    "cloud":{
                        "seeds":[
                            "es02:9300", 
                            "es01:9300"
                        ]
                    }
                }
            }
        }
    }')
    [ $RESPONSE_CODE == "200" ]
}

echo "Adding remote cluster ...";

until addRemoteCluster; do 
    sleep 10;
done

echo "All done!";

