FROM openjdk:17.0.2-jdk

RUN microdnf install wget &&\
    microdnf install unzip &&\
    wget https://services.gradle.org/distributions/gradle-4.10.2-bin.zip &&\
    unzip -d /opt/gradle gradle-4.10.2-bin.zip &&\
    export PATH=$PATH:/opt/gradle/gradle-7.4.2/bin

COPY . /ror

ARG ES_MODULE
ARG ES_VERSION

RUN cd /ror &&\
    ./gradlew clean --stacktrace ${ES_MODULE}:ror -PesVersion=${ES_VERSION} &&\
    mv /ror/${ES_MODULE}/build/distributions/readonlyrest-*.zip /ror/ror.zip
