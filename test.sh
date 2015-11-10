#!/bin/bash
##############################################
Bring up a docker container with ES + plugin
##############################################

# Ensure cleanup
rm -rf docker || true
mkdir docker || true

# Obtain the version to compose the zip file name
PLUGIN_VERSION=`grep plugin_version pom.xml | sed 's|</b>|-|g' | sed 's|<[^>]*>||g' |xargs`
ES_VERSION=`grep '<elasticsearch.version'  pom.xml | sed 's|</b>|-|g' | sed 's|<[^>]*>||g' |xargs`
VERSION=`echo "v$PLUGIN_VERSION es-v$ES_VERSION" |tr " " "_"`

# Dynamically generate docker file from template
cat Dockerfile.tpl |sed -e "s/\${VERSION}/$VERSION/" -e "s/\${PLUGIN_VERSION}/$PLUGIN_VERSION/" -e "s/\${ES_VERSION}/$ES_VERSION/" > docker/Dockerfile

#PLUGIN_ZIP=elasticsearch-readonlyrest-$VERSION.zip

# Populate the conf files with test yml
cp src/test/three_rules.yml docker/elasticsearch.yml
cp target/elasticsearch-readonlyrest-$VERSION.zip docker

# Build and launch docker container
cd docker && docker build -t readonlyrest:$VERSION .
docker run -d --net=host readonlyrest:$VERSION

# Cleanup
rm -rf docker

docker rm -f `docker ps |grep readonlyrest| awk '{print $1}'`
docker run --net host readonlyrest:$VERSION
