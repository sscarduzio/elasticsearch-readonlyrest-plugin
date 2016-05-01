#!/bin/bash
##############################################
# Bring up a docker container with ES + plugin
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

cp target/elasticsearch-readonlyrest-$VERSION.zip docker

# Build and launch docker container
cd docker && docker build -t readonlyrest:$VERSION .
docker run -d --net=host readonlyrest:$VERSION

# Cleanup
rm -rf docker

docker rm -f `docker ps |grep readonlyrest| awk '{print $1}'` || true
docker run -it -p 9200:9200 readonlyrest:$VERSION
