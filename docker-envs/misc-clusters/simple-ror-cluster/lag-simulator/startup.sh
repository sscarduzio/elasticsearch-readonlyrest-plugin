#!/bin/sh -e

toxiproxy-server > /var/log/toxiproxy.log 2>&1 &
sleep 5
toxiproxy-cli create -l 0.0.0.0:19200 -u es-ror:9200 to-es-ror
toxiproxy-cli toxic add -t latency -a latency=200 to-es-ror
toxiproxy-cli toxic add -t reset_peer -a timeout=10 --toxicity 0.0000001 to-es-ror
tail -F /var/log/toxiproxy.log