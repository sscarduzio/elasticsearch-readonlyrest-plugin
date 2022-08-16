#FROM ubuntu:18.04
FROM ghcr.io/shopify/toxiproxy

WORKDIR /work

#RUN apt-get update && \
#    apt-get install curl vim iproute2 iputils-ping dnsutils net-tools netcat socat -y

EXPOSE 13890
#USER root

WORKDIR /work
COPY lag-simulator/startup.sh .
RUN chmod +x startup.sh

ENTRYPOINT [ "/bin/sh", "/work/startup.sh" ]
