# One-time, version-INDEPENDENT gosu base image.
#
# gosu depends only on the CPU architecture, never on the Elasticsearch version, so the alpine + GPG
# download/verify work that used to run inside every per-ES-version image build is hoisted here and
# built once as a single multi-arch image:
#
#   beshultd/elasticsearch-readonlyrest-dev:gosu-1.17
#
# Per-version ROR Dockerfiles then just `COPY --from=<this image> /usr/local/bin/gosu ...`, which is a
# cheap, cached, registry-deduped layer pull instead of re-running the keyserver fetch + GPG verify
# (the slow, flaky part) on every one of the ~247 builds.
#
# Build & push (multi-arch) via: ./gradlew pushGosuBaseImage

FROM alpine:3.21.0

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
	gpgconf --kill all; \
	rm -f /usr/local/bin/gosu.asc; \
	chmod +x /usr/local/bin/gosu; \
	apk del --no-network .gosu-deps; \
	gosu --version; \
	gosu nobody true
