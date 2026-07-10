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

COPY readonlyrest-${ROR_VERSION}_es${ES_VERSION}.zip /tmp/readonlyrest.zip
COPY init-readonlyrest.yml /usr/share/elasticsearch/config/readonlyrest.yml
COPY ror-entrypoint.sh /usr/local/bin/ror-entrypoint.sh

USER root

RUN chmod +x /usr/local/bin/gosu \
    && gosu --version \
    && gosu nobody true

RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/readonlyrest.zip

USER 1000

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/ror-entrypoint.sh"]