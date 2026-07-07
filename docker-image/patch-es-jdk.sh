#!/bin/sh
set -eu

# Replaces the JDK bundled by some Elasticsearch releases when that JDK is affected by cgroup v2 bug
# JDK-8287073: CgroupV2Subsystem.getInstance() NPEs at JVM start (before UseContainerSupport is checked),
# so ES never boots on modern (cgroup v2) Docker/K8s hosts.
#
# Affected: JDK 17.0.0-17.0.4 and JDK 18, bundled by ES 7.15.1-7.17.6 and 8.0.x-8.4.x. Fixed in
# JDK 17.0.5+ (backport JDK-8288308) and JDK 19+. We swap the bundled JDK for an Amazon Corretto build
# that carries the fix: Corretto 17.0.5 for the JDK-17 releases, Corretto 19.0.0 for the JDK-18 releases.
#
# The script self-gates on $ES_VERSION and is a NO-OP for any unaffected version, so a Dockerfile can run
# it unconditionally. The version-range logic is the single source of truth shared with the test images in
# tests-utils/.../images/Elasticsearch.scala (hasBuggyBundledJdk / needsCorretto19) and the e2e-tests repo
# (environments/common/images/es-jdk-patch/patch-es-jdk.sh) -- keep the three in sync.
#
# Requires $ES_VERSION (X.Y.Z) in the environment and must run as root (rewrites the ES install dir).

if [ -z "${ES_VERSION:-}" ]; then
  echo "patch-es-jdk: ES_VERSION not set" >&2
  exit 1
fi

MAJOR=$(echo "$ES_VERSION" | cut -d. -f1)
MINOR=$(echo "$ES_VERSION" | cut -d. -f2)
PATCH=$(echo "$ES_VERSION" | cut -d. -f3 | cut -d- -f1)

# Collapse to a single comparable integer (minor/patch always < 100), e.g. 7.15.1 -> 71501, 8.4.3 -> 80403.
V=$((MAJOR * 10000 + MINOR * 100 + PATCH))

# hasBuggyBundledJdk: [7.15.1, 7.17.7) or [8.0.0, 8.5.0)
if { [ "$V" -ge 71501 ] && [ "$V" -lt 71707 ]; } || { [ "$V" -ge 80000 ] && [ "$V" -lt 80500 ]; }; then
  HAS_BUGGY=1
else
  HAS_BUGGY=0
fi

# needsCorretto19 (bundled JDK 18): [7.17.3, 7.17.7) or [8.2.0, 8.5.0)
if { [ "$V" -ge 71703 ] && [ "$V" -lt 71707 ]; } || { [ "$V" -ge 80200 ] && [ "$V" -lt 80500 ]; }; then
  NEEDS_19=1
else
  NEEDS_19=0
fi

if [ "$HAS_BUGGY" -eq 0 ]; then
  echo "patch-es-jdk: ES $ES_VERSION bundles a JDK unaffected by JDK-8287073; no swap needed."
  exit 0
fi

if [ "$NEEDS_19" -eq 1 ]; then
  CORRETTO_VERSION="19.0.0.36.1"
else
  CORRETTO_VERSION="17.0.5.8.1"
fi

ARCH=$(uname -m | sed 's/x86_64/x64/' | sed 's/arm64/aarch64/')
echo "patch-es-jdk: ES $ES_VERSION bundles a JDK affected by JDK-8287073; replacing with Amazon Corretto ${CORRETTO_VERSION} (${ARCH})."

curl -fsSLk "https://corretto.aws/downloads/resources/${CORRETTO_VERSION}/amazon-corretto-${CORRETTO_VERSION}-linux-${ARCH}.tar.gz" -o /tmp/jdk.tar.gz
rm -rf /usr/share/elasticsearch/jdk
mkdir -p /usr/share/elasticsearch/jdk
tar xzf /tmp/jdk.tar.gz -C /usr/share/elasticsearch/jdk --strip-components=1
rm /tmp/jdk.tar.gz

# Corretto 19 replaces bundled Oracle JDK 18, whose jdk.net module is otherwise unavailable. Apache
# HttpClient 5's ReflectionUtils accesses jdk.net.Sockets and throws NoClassDefFoundError (not caught by
# its static initializer) without it, so expose the module via a ROR jvm.options.d drop-in. See
# core/.../accesscontrol/factory/ApacheBasedSimpleHttpClient.scala.
if [ "$NEEDS_19" -eq 1 ]; then
  mkdir -p /usr/share/elasticsearch/config/jvm.options.d
  echo "--add-modules=jdk.net" > /usr/share/elasticsearch/config/jvm.options.d/ror.options
  chown elasticsearch:elasticsearch /usr/share/elasticsearch/config/jvm.options.d/ror.options
fi
