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

echo "Installing ES ROR from file..."
/usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/ror.zip
ROR_VERSION=$(unzip -p /tmp/ror.zip plugin-descriptor.properties | grep -oP '^version=\K.*')

if verlte "6.5.0" "$ES_VERSION"; then
  echo "Patching ES ROR $ROR_VERSION..."
  if verlte "1.64.0" "$ROR_VERSION"; then
    /usr/share/elasticsearch/jdk/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar patch --I_UNDERSTAND_AND_ACCEPT_ES_PATCHING=yes
  else
    /usr/share/elasticsearch/jdk/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar patch
  fi
fi
echo "DONE!"