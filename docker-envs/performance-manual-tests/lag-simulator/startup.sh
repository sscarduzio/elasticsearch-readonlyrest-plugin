#!/bin/sh

toxiproxy-cli create -l localhost:13890 -u localhost:3890 tolldap
toxiproxy-cli toxic add -t latency -a latency=1000 tolldap

## you will need to change hostname and ip to suite your needs
#
## destination ip (connect was a hostname of a docker container)
#DESTINATION_IP=$(dig lldap +short)
#echo
## traffic control to create 100ms delay
#tc qdisc add dev eth0 root netem delay 9000ms
#
## socat multipurpose relay
#socat tcp-listen:13890,reuseaddr,fork tcp:$DESTINATION_IP:3890
#
#
## change to random delay
## tc qdisc change dev eth0 root netem delay 100ms 10ms
#
## delete
## tc qdisc del dev eth0 root netem
#
## add 250ms delay
## tc qdisc add dev eth0 root netem delay 250ms
#
## add random delay
## tc qdisc add dev eth0 root netem delay 100ms 10ms