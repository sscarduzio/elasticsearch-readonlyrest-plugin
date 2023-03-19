FROM openjdk:17.0.2-jdk

RUN microdnf install wget &&\
    microdnf install unzip &&\
    wget https://services.gradle.org/distributions/gradle-7.6.1-bin.zip &&\
    unzip -d /opt/gradle gradle-7.6.1-bin.zip &&\
    export PATH=$PATH:/opt/gradle/gradle-7.6.1/bin

COPY ./ /ror
WORKDIR /ror

ENTRYPOINT ["/ror/bin/build-ror-plugin.sh"]
