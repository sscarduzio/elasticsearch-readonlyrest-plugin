#!/bin/sh -e

toxiproxy-server &
toxiproxy-cli create -l 0.0.0.0:13890 -u localhost:3890 tolldap
toxiproxy-cli toxic add -t latency -a latency=5000 tolldap
toxiproxy-cli list