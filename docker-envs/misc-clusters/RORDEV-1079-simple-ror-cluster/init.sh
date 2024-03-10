#!/bin/bash

curl -vk -u admin:container 'https://localhost:19201/mysample/_doc/1' -XPUT -H "Content-Type: application/json" -d '{ "a" : 1}'
curl -vk -u admin:container 'https://localhost:19201/mysample/_doc/2' -XPUT -H "Content-Type: application/json" -d '{ "a" : 1}'
curl -vk -u admin:container 'https://localhost:19201/mysample/_doc/3' -XPUT -H "Content-Type: application/json" -d '{ "a" : 1}'

curl -kv -u admin:container 'https://localhost:19201/_snapshot/repo1' -XPUT -H "Content-Type: application/json" -d '{"type":"fs","settings":{"location":"/tmp/data"}}'
curl -kv -u admin:container 'https://localhost:19201/_snapshot/repo1/test014' -XPUT -H "Content-Type: application/json" -d '{"indices":"mysample"}'

# curl -kv -u admin:container 'https://localhost:19204/_snapshot/repo1/test014/_status'