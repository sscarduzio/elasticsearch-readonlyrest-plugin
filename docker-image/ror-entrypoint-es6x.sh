#!/bin/bash -e

INVOKE_ROR_TOOLS="$JAVA_HOME/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar"

if ! $INVOKE_ROR_TOOLS verify; then

  if [ -n "$I_UNDERSTAND_AND_ACCEPT_ES_PATCHING" ]; then
      CONFIRMATION="$I_UNDERSTAND_AND_ACCEPT_ES_PATCHING"
  elif [ -n "$I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING" ]; then
      CONFIRMATION="$I_UNDERSTAND_IMPLICATION_OF_ES_PATCHING"
  else
      CONFIRMATION=""
  fi

  if [[ "${CONFIRMATION,,}" == *"yes"* ]]; then
    if [ "$(id -u)" -ne 0 ]; then
      echo "Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation:" \
           "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch. To patch Elasticsearch the container " \
           "has to be run as root. Then, after the patching step, the Elasticsearch process will be run as 'elasticsearch' " \
           "user. "
      exit 1
    else
      $INVOKE_ROR_TOOLS patch --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING yes
    fi
  else
    echo "Elasticsearch needs to be patched to work with ReadonlyREST. You can read about patching in our documentation:" \
         "https://docs.readonlyrest.com/elasticsearch#id-3.-patch-elasticsearch. In this Docker image, the patching step" \
         "is done automatically, but you must agree to have it done for you. You can do this by setting" \
         "I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=yes environment variable when running the container."
    exit 2
  fi

else
  echo "Elasticsearch is already patched. We can continue ..."
fi

# CVE-2026-114938 (Java4Shell): the JDK Base64 decoder intrinsic can corrupt the heap on x86-64 CPUs with
# AVX-512 VBMI. The JVM uses the last value of a flag. Thus the user's ES_JAVA_OPTS can override this one.
# Remove the flag when no supported ES version bundles a vulnerable JDK.
export ES_JAVA_OPTS="-XX:-UseBASE64Intrinsics${ES_JAVA_OPTS:+ $ES_JAVA_OPTS}"

# exec: Elasticsearch must replace this shell. Without it, on docker stop the shell gets SIGTERM
# and dies (or ignores it as PID 1), and Elasticsearch is killed without a graceful stop.
exec gosu elasticsearch /usr/local/bin/docker-entrypoint.sh "$@"
