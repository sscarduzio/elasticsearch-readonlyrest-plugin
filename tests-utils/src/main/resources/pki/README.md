# PKI integration test certificates

Regenerate with `./generate.sh`. Every store uses the password `readonlyrest`.

The distinguished names are part of the test contract: `PkiAuthSuite` asserts on the username and
groups the ACL reads out of them, so they have to stay in step with
`integration-tests/src/test/resources/pki/readonlyrest.yml`.

| Keystore | Subject | Issuer | What it is for |
|---|---|---|---|
| `pki-ca.jks` | `CN=ROR Test CA,DC=corp,DC=example,DC=com` | itself | signs everything the node trusts |
| `pki-rogue-ca.jks` | `CN=ROR Rogue CA,DC=corp,DC=example,DC=com` | itself | signs the one certificate the node must reject |
| `pki-truststore.jks` | — | — | holds the test CA; what the node checks client certificates against |
| `pki-server.jks` | `CN=localhost,DC=corp,…` | test CA | the node's own TLS identity |
| `pki-svc-logstash.jks` | `CN=svc-logstash,OU=svc-ingest,OU=Services,DC=corp,…` | test CA | a machine identity in the services branch, carrying the `svc-ingest` role |
| `pki-svc-dashboard.jks` | `CN=svc-dashboard,OU=svc-query,OU=Services,DC=corp,…` | test CA | a second machine identity, carrying a different role |
| `pki-jsmith.jks` | `CN=jsmith,OU=svc-ingest,OU=People,DC=corp,…` | test CA | trusted, but outside the provider's `subject_dn_base` — proves the scope constraint bites |
| `pki-rogue.jks` | `CN=svc-logstash,OU=svc-ingest,OU=Services,DC=corp,…` | **rogue CA** | the same subject as `svc-logstash` from a CA the node does not trust; the handshake must fail before the ACL ever runs |

The first OU of a subject is the role the ACL maps to a local group; the OUs after it place the
certificate in the corporate tree, which is what `subject_dn_base` filters on. `pki-rogue.jks` exists
because a subject DN alone proves nothing — any CA can mint any name — which is the reason
`issuer_dn` is offered as a second constraint.
