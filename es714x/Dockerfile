ARG ES_VERSION
ARG ROR_VERSION

FROM alpine:3.21.0 AS builder

ENV GOSU_VERSION=1.17
RUN set -eux; \
	apk add --no-cache --virtual .gosu-deps \
		ca-certificates \
		dpkg \
		gnupg \
	; \
	dpkgArch="$(dpkg --print-architecture | awk -F- '{ print $NF }')"; \
	wget -O /usr/local/bin/gosu "https://github.com/tianon/gosu/releases/download/$GOSU_VERSION/gosu-$dpkgArch"; \
	wget -O /usr/local/bin/gosu.asc "https://github.com/tianon/gosu/releases/download/$GOSU_VERSION/gosu-$dpkgArch.asc"; \
    # verify the signature
	export GNUPGHOME="$(mktemp -d)"; \
	gpg --batch --keyserver hkps://keys.openpgp.org --recv-keys B42F6819007F00F88E364FD4036A9C25BF357DD4; \
	gpg --batch --verify /usr/local/bin/gosu.asc /usr/local/bin/gosu; \
	gpgconf --kill all;

FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}
COPY --from=builder /usr/local/bin/gosu /usr/local/bin/gosu

ARG ES_VERSION
ARG ROR_VERSION

ENV KIBANA_USER_PASS=kibana
ENV ADMIN_USER_PASS=admin

USER root

RUN chmod +x /usr/local/bin/gosu \
    && gosu --version \
    && gosu nobody true \
    # ES 7.15.1 and 7.15.2 bundle JDK 17.0.0/17.0.1 which crash on cgroup v2 hosts (JDK-8287073). \
    # Replace with Corretto 17.0.5 which contains the fix (backport JDK-8288308). \
    # ES 7.14.x bundles JDK 16, which is not affected. \
    && ES_MINOR=$(echo "${ES_VERSION}" | cut -d. -f2) \
    && ES_PATCH=$(echo "${ES_VERSION}" | cut -d. -f3) \
    && if [ "${ES_MINOR}" -eq "15" ] && [ "${ES_PATCH}" -ge "1" ]; then \
           ARCH="$(uname -m | sed 's/x86_64/x64/' | sed 's/arm64/aarch64/')"; \
           curl -fsSLk "https://corretto.aws/downloads/resources/17.0.5.8.1/amazon-corretto-17.0.5.8.1-linux-${ARCH}.tar.gz" -o /tmp/jdk.tar.gz; \
           rm -rf /usr/share/elasticsearch/jdk; \
           mkdir -p /usr/share/elasticsearch/jdk; \
           tar xzf /tmp/jdk.tar.gz -C /usr/share/elasticsearch/jdk --strip-components=1; \
           rm /tmp/jdk.tar.gz; \
       fi

USER elasticsearch

COPY readonlyrest-${ROR_VERSION}_es${ES_VERSION}.zip /tmp/readonlyrest.zip
COPY init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror-entrypoint.sh /usr/local/bin/ror-entrypoint.sh

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/readonlyrest.zip

ENTRYPOINT ["/bin/tini", "--", "/usr/local/bin/ror-entrypoint.sh"]