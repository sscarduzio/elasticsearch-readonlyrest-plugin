#!/bin/bash -e

INVOKE_ROR_TOOLS="$JAVA_HOME/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar"

if $INVOKE_ROR_TOOLS verify | grep -q "Elasticsearch is NOT patched"; then
  $INVOKE_ROR_TOOLS patch
else
  echo "Elasticsearch is already patched. We can continue ..."
fi

gosu elasticsearch /usr/local/bin/docker-entrypoint.sh "$@"