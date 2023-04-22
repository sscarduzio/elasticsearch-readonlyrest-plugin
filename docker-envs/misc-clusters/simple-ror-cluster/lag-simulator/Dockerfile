FROM ubuntu:20.04

WORKDIR /work

RUN apt-get update && \
    apt-get install wget -y && \
    apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* && \
    wget -O toxiproxy-2.5.0.deb https://github.com/Shopify/toxiproxy/releases/download/v2.5.0/toxiproxy_2.5.0_linux_amd64.deb && \
    dpkg -i toxiproxy-2.5.0.deb

EXPOSE 19200

COPY lag-simulator/startup.sh .
RUN chmod +x startup.sh

ENTRYPOINT [ "/bin/sh", "/work/startup.sh" ]
