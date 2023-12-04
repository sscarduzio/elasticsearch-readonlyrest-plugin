#!/bin/bash

for i in $(seq 0 1003); do
    curl -vk -u admin:container -XPUT "https://localhost:19200/example/_doc/$i" -H "Content-type: application/json" -d '{"df_country": "UK", "price": 100, "df_date": "2023-12-21T11:47:22.992000"}'
done