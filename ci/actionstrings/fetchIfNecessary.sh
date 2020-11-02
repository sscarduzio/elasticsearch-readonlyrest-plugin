ES_VERSION=$1

DEST_DIR="readonlyrest-docs/actionstrings"
mkdir -p $DEST_DIR || echo "$DEST_DIR already present."

FILENAME="$DEST_DIR/action_strings_es$ES_VERSION.txt"
if test -f $FILENAME; then
  echo "$FILENAME exists."
else
  echo "processing ES action extractions for: $FILENAME"
  ci/actionstrings/fetch.sh "v$ES_VERSION" > $FILENAME
  head -3 $FILENAME
  echo "..."
  tail -3 $FILENAME
fi

