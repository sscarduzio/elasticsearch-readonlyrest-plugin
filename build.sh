#!/bin/bash
mvn package
zip -g target/elasticsearch-readonlyrest-v*es-v2*.zip src/main/resources/plugin-descriptor.properties
