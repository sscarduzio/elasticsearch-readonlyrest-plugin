FROM openjdk:17.0.2-jdk

RUN microdnf install wget &&\
    microdnf install unzip &&\
    wget https://services.gradle.org/distributions/gradle-4.10.2-bin.zip &&\
    unzip -d /opt/gradle gradle-4.10.2-bin.zip &&\
    export PATH=$PATH:/opt/gradle/gradle-7.4.2/bin

COPY ./../ /ror
WORKDIR /ror

ENV ES_VERSION=8.3.3

RUN echo -e '#!/bin/bash \n ./bin/build-ror-plugin.sh $ES_VERSION' > build-ror.sh &&\
    chmod +x build-ror.sh

ENTRYPOINT /ror/build-ror.sh
