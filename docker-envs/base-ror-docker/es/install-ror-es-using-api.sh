#!/bin/bash -e

function verlte() {
  [ "$1" = "`echo -e "$1\n$2" | sort -V | head -n1`" ]
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
/usr/share/elasticsearch/bin/elasticsearch-plugin install --batch "https://api.beshu.tech/download/es?esVersion=$ES_VERSION&pluginVersion=$ROR_VERSION"
if verlte "8.0.0" "$ES_VERSION"; then
  echo "Patching ES ROR $ROR_VERSION..."
  /usr/share/elasticsearch/jdk/bin/java -jar /usr/share/elasticsearch/plugins/readonlyrest/ror-tools.jar patch
fi
echo "DONE!"
