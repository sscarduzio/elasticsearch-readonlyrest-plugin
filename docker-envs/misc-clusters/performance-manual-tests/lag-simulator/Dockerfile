FROM ubuntu:20.04

WORKDIR /work

RUN apt-get update && \
    apt-get install wget -y && \
    apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* && \
    ARCHITECTURE=$(dpkg --print-architecture) && \
    if [ "$ARCHITECTURE" = "arm64" ]; then \
      TOXIPROXY_DEB_URL="https://github.com/Shopify/toxiproxy/releases/download/v2.4.0/toxiproxy_2.4.0_linux_arm64.deb"; \
    else \
      TOXIPROXY_DEB_URL="https://github.com/Shopify/toxiproxy/releases/download/v2.4.0/toxiproxy_2.4.0_linux_amd64.deb"; \
    fi && \
    wget -O toxiproxy-2.4.0.deb $TOXIPROXY_DEB_URL && \
    dpkg -i toxiproxy-2.4.0.deb

EXPOSE 13890

COPY lag-simulator/startup.sh .
RUN chmod +x startup.sh

ENTRYPOINT [ "/bin/sh", "/work/startup.sh" ]
