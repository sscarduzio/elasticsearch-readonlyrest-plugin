#!/bin/bash
##############################################
# Bring up a docker container with ES + plugin
##############################################

# Ensure cleanup
rm -rf docker || true
mkdir docker || true

# Obtain the version to compose the zip file name
PLUGIN_VERSION=`grep 'pluginVersion ='  build.gradle | awk {'print $3'} |tr -d "\'"`
ES_VERSION=`grep 'esVersion ='  build.gradle | awk {'print $3'} |tr -d "\'"`
VERSION=`echo "v$PLUGIN_VERSION es$ES_VERSION" |tr " " "_"`

# Dynamically generate docker file from template
cat Dockerfile.tpl |sed -e "s/\${VERSION}/$VERSION/" -e "s/\${PLUGIN_VERSION}/$PLUGIN_VERSION/" -e "s/\${ES_VERSION}/$ES_VERSION/" > docker/Dockerfile

if [ ! -z "$1" ]
then
 CONF_FILE="$1"
else
 CONF_FILE="src/test/test_rules.yml"
fi
echo ">> Using conf file $CONF_FILE"
cat $CONF_FILE

# Populate the conf files with test yml
cp $CONF_FILE docker/elasticsearch.yml

cp build/distributions/readonlyrest-$VERSION.zip docker
cp src/test/eshome/plugins/readonlyrest/keystore.jks docker

# Build and launch docker container
cd docker && docker build -t readonlyrest:$VERSION .
docker run -d --net=host readonlyrest:$VERSION

# Cleanup
rm -rf docker

docker rm -f `docker ps |grep readonlyrest| awk '{print $1}'` || true
docker run -it -p 9200:9200 -p8000:8000 readonlyrest:$VERSION
