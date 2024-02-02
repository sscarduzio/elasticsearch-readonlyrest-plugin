#!/bin/bash -e

INVOKE_ROR_TOOLS="/usr/share/elasticsearch/jdk/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar"

if $INVOKE_ROR_TOOLS verify | grep -q "Elasticsearch is NOT patched"; then

  if [ -n "$I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING" ] && [[ "${I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING,,}" == *"yes"* ]]; then
    $INVOKE_ROR_TOOLS patch
  else
    echo "Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation:" \
         "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch. In this Docker image, the patching step" \
         "is done automatically, but you must agree to have it done for you. You can do this by setting" \
         "I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING=yes environment when running the container."
    exit 1
  fi

else
  echo "Elasticsearch is already patched. We can continue ..."
fi

gosu elasticsearch /usr/local/bin/docker-entrypoint.sh "$@"