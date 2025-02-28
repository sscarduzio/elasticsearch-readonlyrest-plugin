#!/bin/bash -e

INVOKE_ROR_TOOLS="$JAVA_HOME/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar"

if $INVOKE_ROR_TOOLS verify | grep -q "Elasticsearch is NOT patched"; then

  if [ -n "$I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING" ] && [[ "${I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING,,}" == *"yes"* ]]; then
    if [ "$(id -u)" -ne 0 ]; then
      echo "Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation:" \
           "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch. To patch Elasticsearch the container " \
           "has to be run as root. Then, after the patching step, the Elasticsearch process will be run as 'elasticsearch' " \
           "user. "
      exit 1
    else
      $INVOKE_ROR_TOOLS patch --I-understand-implications-of-ES-patching yes
    fi
  else
    echo "Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation:" \
         "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch. In this Docker image, the patching step" \
         "is done automatically, but you must agree to have it done for you. You can do this by setting" \
         "I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING=yes environment variable when running the container."
    exit 2
  fi

else
  echo "Elasticsearch is already patched. We can continue ..."
fi


gosu elasticsearch /usr/local/bin/docker-entrypoint.sh "$@"