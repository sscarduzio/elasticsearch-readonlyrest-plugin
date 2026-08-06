#!/usr/bin/env bash
#
# Regenerates the keystores used by the PKI integration tests.
#
# The client certificates carry the identity the ACL is expected to read out of them, so their
# distinguished names are part of the test contract and must stay in step with
# integration-tests/src/test/resources/pki/readonlyrest.yml:
#
#   CN  -> the ROR username
#   OU  -> the external groups (the first OU of the DN is the role; the rest place the certificate
#          in the corporate tree, which is what subject_dn_base filters on)
#
# Run from this directory:  ./generate.sh
#
set -euo pipefail

STORE_PASS="readonlyrest"
KEY_PASS="readonlyrest"
VALIDITY_DAYS=7300 # 20 years, so that the fixtures do not silently expire mid-decade
KEY_ALG="RSA"
KEY_SIZE="2048"
SIG_ALG="SHA256withRSA"

BASE_DN="DC=corp,DC=example,DC=com"
CA_DN="CN=ROR Test CA,${BASE_DN}"
ROGUE_CA_DN="CN=ROR Rogue CA,${BASE_DN}"

rm -f ./*.jks ./*.pem ./*.csr

keystore_of() { echo "pki-$1.jks"; }

new_ca() {
  local file="$1" dn="$2"
  keytool -genkeypair -alias ca -dname "${dn}" \
    -keyalg "${KEY_ALG}" -keysize "${KEY_SIZE}" -sigalg "${SIG_ALG}" \
    -validity "${VALIDITY_DAYS}" -ext "BasicConstraints:critical=ca:true" \
    -keystore "${file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}" -storetype JKS
}

# Signs a fresh key pair with the given CA and leaves the full chain in its own keystore.
new_signed_keystore() {
  local ca_file="$1" name="$2" dn="$3" extra_ext="${4:-}"
  local file
  file="$(keystore_of "${name}")"

  keytool -genkeypair -alias "${name}" -dname "${dn}" \
    -keyalg "${KEY_ALG}" -keysize "${KEY_SIZE}" -sigalg "${SIG_ALG}" \
    -validity "${VALIDITY_DAYS}" \
    -keystore "${file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}" -storetype JKS

  keytool -certreq -alias "${name}" -file "${name}.csr" \
    -keystore "${file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}"

  if [[ -n "${extra_ext}" ]]; then
    keytool -gencert -alias ca -infile "${name}.csr" -outfile "${name}.pem" \
      -sigalg "${SIG_ALG}" -validity "${VALIDITY_DAYS}" -ext "${extra_ext}" \
      -keystore "${ca_file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}" -rfc
  else
    keytool -gencert -alias ca -infile "${name}.csr" -outfile "${name}.pem" \
      -sigalg "${SIG_ALG}" -validity "${VALIDITY_DAYS}" \
      -keystore "${ca_file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}" -rfc
  fi

  # the CA has to be in the keystore before the signed reply, or the chain cannot be built
  keytool -exportcert -alias ca -keystore "${ca_file}" -storepass "${STORE_PASS}" -rfc -file ca-of-"${name}".pem
  keytool -importcert -noprompt -alias ca -file ca-of-"${name}".pem \
    -keystore "${file}" -storepass "${STORE_PASS}"
  keytool -importcert -noprompt -alias "${name}" -file "${name}.pem" \
    -keystore "${file}" -storepass "${STORE_PASS}" -keypass "${KEY_PASS}"

  # The signed reply carries the whole chain, so the separate CA entry has done its job. It has to go:
  # ReadonlyREST walks the aliases of a keystore looking for the private key, and a trusted-certificate
  # entry sitting in front of it fails the boot with "Configured key with alias=ca is not a private key".
  keytool -delete -alias ca -keystore "${file}" -storepass "${STORE_PASS}"

  rm -f "${name}.csr" "${name}.pem" ca-of-"${name}".pem
}

echo "==> certificate authorities"
new_ca "pki-ca.jks" "${CA_DN}"
new_ca "pki-rogue-ca.jks" "${ROGUE_CA_DN}"

echo "==> truststore (what the ES node trusts client certificates against)"
keytool -exportcert -alias ca -keystore "pki-ca.jks" -storepass "${STORE_PASS}" -rfc -file ca.pem
keytool -importcert -noprompt -alias ca -file ca.pem \
  -keystore "pki-truststore.jks" -storepass "${STORE_PASS}" -storetype JKS
rm -f ca.pem

echo "==> server certificate"
new_signed_keystore "pki-ca.jks" "server" "CN=localhost,${BASE_DN}" \
  "SubjectAlternativeName=DNS:localhost,IP:127.0.0.1"

echo "==> client certificates"
# a machine identity inside the services branch, carrying the 'svc-ingest' role
new_signed_keystore "pki-ca.jks" "svc-logstash" \
  "CN=svc-logstash,OU=svc-ingest,OU=Services,${BASE_DN}"
# a second machine identity, carrying a different role
new_signed_keystore "pki-ca.jks" "svc-dashboard" \
  "CN=svc-dashboard,OU=svc-query,OU=Services,${BASE_DN}"
# a human, in the people branch - trusted by the same CA, but outside the provider's subject_dn_base
new_signed_keystore "pki-ca.jks" "jsmith" \
  "CN=jsmith,OU=svc-ingest,OU=People,${BASE_DN}"
# a certificate the node does not trust at all - the handshake must fail
new_signed_keystore "pki-rogue-ca.jks" "rogue" \
  "CN=svc-logstash,OU=svc-ingest,OU=Services,${BASE_DN}"

echo
echo "==> generated:"
ls -1 ./*.jks
