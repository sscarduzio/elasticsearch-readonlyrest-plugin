#!/bin/bash

verlte() {
  local v1="$1"
  local v2="${2%%[-+]*}"  # Strip suffix from second argument in order to support -pre versions
  [ "$v1" = "$(echo -e "$v1\n$v2" | sort -V | head -n1)" ]
}

if [[ -z "$ES_VERSION" ]]; then
  echo "No ES_VERSION variable is set"
  exit 1
fi

if [[ -z "$ROR_VERSION" ]]; then
  echo "No $ROR_VERSION variable is set"
  exit 2
fi

echo "Installing ES ROR $ROR_VERSION..."
/usr/share/elasticsearch/bin/elasticsearch-plugin install --batch "https://api.beshu.tech/download/es?esVersion=$ES_VERSION&pluginVersion=$ROR_VERSION&email=ror-sandbox%40readonlyrest.com"

if [[ -z "$ROR_VERSION" ]]; then
  echo "No $ROR_VERSION variable is set"
  exit 2
fi

# Set Java path based on ES version
if verlte "7.0.0" "$ES_VERSION"; then
  JAVA_BIN_PATH="/usr/share/elasticsearch/jdk/bin/java"
elif verlte "6.7.0" "$ES_VERSION"; then
  JAVA_BIN_PATH="$JAVA_HOME/bin/java"
else
  echo "Unsupported ES version: $ES_VERSION"
  exit 1
fi

# Set OPTIONS based on ROR version
if verlte "1.64.0" "$ROR_VERSION"; then
  OPTIONS="--I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=yes"
else
  OPTIONS=""
fi

$JAVA_BIN_PATH -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar patch $OPTIONS
echo "DONE!"
