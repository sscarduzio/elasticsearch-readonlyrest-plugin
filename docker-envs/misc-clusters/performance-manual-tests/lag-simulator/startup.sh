#!/bin/sh -e

toxiproxy-server > /var/log/toxiproxy.log 2>&1 &
sleep 5
toxiproxy-cli create -l 0.0.0.0:13890 -u ldap:3890 to-ldap
toxiproxy-cli toxic add -t latency -a latency=2000 to-ldap
tail -F /var/log/toxiproxy.log