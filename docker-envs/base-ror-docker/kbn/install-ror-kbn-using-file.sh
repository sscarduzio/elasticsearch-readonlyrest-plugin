#!/bin/bash -e

echo "Installing KBN ROR from file..."
/usr/share/kibana/bin/kibana-plugin install file:///tmp/ror.zip
echo "Patching KBN ROR..."
/usr/share/kibana/node/bin/node plugins/readonlyrestkbn/ror-tools.js patch
echo "DONE!"