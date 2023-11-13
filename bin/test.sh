#!/bin/bash

version() {
  echo "$@" | awk -F. '{ printf("%d%03d%03d\n", $1,$2,$3); }';
}

vergte() {
  [ $(version $1) -ge $(version $2) ]
}
