FROM ubuntu:23.10

COPY ./ /ror
WORKDIR /ror

ENTRYPOINT ["/ror/bin/build-ror-plugin.sh"]
