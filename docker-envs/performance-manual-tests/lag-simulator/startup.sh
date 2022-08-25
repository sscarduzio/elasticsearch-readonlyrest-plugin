#!/bin/sh -e

toxiproxy-server > /var/log/toxiproxy.log 2>&1 &
sleep 5
toxiproxy-cli create -l 0.0.0.0:13890 -u lldap:3890 tolldap
toxiproxy-cli toxic add -t latency -a latency=5000 tolldap
tail -F /var/log/toxiproxy.log