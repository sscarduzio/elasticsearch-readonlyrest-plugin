# RFC — PKI-based Authentication and Authorization in the ReadonlyREST ACL

| | |
|---|---|
| **Status** | Draft — design agreed, pending implementation review |
| **Author** | Architecture |
| **Date** | 2026-08-06 |
| **Audience** | ROR engineering, product |
| **Scope** | Functional specification + high-level design. No implementation design. |

---

## 1. Problem statement

### 1.1 What exists today

ReadonlyREST can terminate TLS for the Elasticsearch HTTP layer, and Elasticsearch itself can do
the same when X-Pack Security is enabled. Both support **client certificate authentication**: in
ROR's case via `readonlyrest.ssl.client_authentication` plus a truststore or trusted-CA file; in
X-Pack's case via `xpack.security.http.ssl.*`.

In both cases the verified peer identity is consumed by the TLS handshake and then discarded. The
request abstraction the ACL evaluates exposes the HTTP method, path, headers, local/remote address
and body — but no peer certificate, no subject DN, no SAN. The ACL therefore has **no way to know
who the caller is** based on the certificate they just proved they own.

### 1.2 The limitation

mTLS in ROR today is a **transport-level gate**, not an **identity**. It answers "is this client
allowed to open a connection?" — one global, binary question — and cannot answer "which client is
this, and what may they do?".

The practical consequences:

- **Every mTLS client is anonymous to the ACL.** Once the handshake succeeds, all clients are
  indistinguishable. There is no per-client access control.
- **A second credential is mandatory.** Any service connecting over mTLS must *also* present a
  username/password, a JWT, a ROR Kibana token, or a trusted proxy header, purely so the ACL has
  something to key on. The certificate the client already proved ownership of is wasted.
- **The strongest credential present is the one not used.** A private key in a service's keystore
  is a materially better credential than a password in a `logstash.yml` — but ROR's ACL can only
  act on the weaker one.

### 1.3 Customer use cases not supported today

| # | Use case | Why it fails today |
|---|---|---|
| 1 | **Logstash / Beats fleet, ID-less** — shippers authenticate with per-host certificates from the corporate CA; each may write only to its own index prefix. | ROR cannot derive an identity from the certificate, so every shipper needs a password on every edge host. |
| 2 | **Kibana → ES service account over mTLS** | The Kibana service credential must still be a username/password in `kibana.yml`. |
| 3 | **Regulated environments with a "no shared secrets" policy** — banking, defence, healthcare. | ROR cannot satisfy the policy. Customers get an exception (expensive) or choose a competitor. |
| 4 | **Third-party / partner service integration** — a partner is issued a certificate with its role encoded in the DN. | ROR cannot read the DN and cannot map it to groups. |
| 5 | **Mixed human + machine access on one port** — analysts via LDAP, services via certificates. | Possible today at the TLS layer only if client auth is `optional`; but with no PKI rule, the certificate still carries no identity. |
| 6 | **Migration from the X-Pack PKI realm** | ROR has no equivalent; migration is a functional regression. |
| 7 | **Certificate-derived groups without a directory** — air-gapped cluster, authorization data only in certificate attributes. | No mechanism exists. |

This is not hypothetical. An enterprise banking customer currently running ROR has asked for
exactly use cases 1, 2 and 4 — described as an "ID-less approach" for component-to-component
communication where "all the communication is based on certificate" — and a proof of concept has
been committed. That engagement is the immediate driver for this work.

### 1.4 Why this is valuable

- **Closes a competitive gap.** The X-Pack PKI realm has existed since 6.x. Its absence is a
  recurring objection in regulated-industry deals, and it is a hard blocker rather than a
  preference: a security policy forbidding stored passwords cannot be argued with.
- **Removes a class of secrets.** The dominant credential-leak vector in ES deployments is a
  password in a Beats/Logstash config file, on disk, on many hosts, often in version control.
- **Leverages infrastructure customers already run.** Enterprises asking for this already operate a
  CA, an issuance process, and rotation tooling.
- **Extends ROR's reach rather than duplicating it.** Certificate identity plugs into the existing
  group/authorization machinery — LDAP group lookup, the `users` section, group mapping — so the
  incremental surface is identity *extraction*; everything downstream is already built and tested.

---

## 2. Goals

### 2.1 Goals

1. **Certificate-based authentication.** Derive a ROR user identity from a client certificate the
   TLS layer has already verified. The certificate is the sole credential.
2. **Service-to-service ("ID-less") authentication** for Kibana, Logstash, Beats and third-party
   services.
3. **First-class ACL integration.** PKI identity must be usable by every existing rule (`indices`,
   `actions`, `kibana`, `fields`, `filter`, `groups`, …) exactly as any other identity.
4. **Certificate-based authorization.** Support deriving groups from the certificate itself, for
   deployments with no directory service.
5. **Both TLS terminators.** Work whether TLS is terminated by **X-Pack** (the recommended
   configuration for new deployments, and therefore the primary path) or by **ROR's own SSL**
   (widely deployed today, including by the customer driving this work).
6. **Consistency with existing ROR concepts.** Minimal new vocabulary:
   - a definitions section shaped like `ldaps`;
   - three ACL rules named and behaving like `ldap_authentication` / `ldap_authorization` /
     `ldap_auth`;
   - participation in the `users` section and its group mapping, on LDAP's terms;
   - the same `name:`-references-a-definition pattern used by every pluggable ROR provider.
7. **Composition with other sources.** A block must be able to authenticate via PKI and authorize
   via LDAP — the combination most large customers will deploy.
8. **Mixed-mode operation on a single port.** Certificate-bearing services and password/LDAP-bearing
   humans share one HTTPS endpoint; a client without a certificate falls through to other blocks.
9. **Strict backward compatibility.** Existing configurations behave identically after upgrade.
10. **Auditability.** Certificate-authenticated requests appear in audit as normal authenticated
    requests.

### 2.2 Non-goals

| Not in scope | Rationale |
|---|---|
| PKI on the **internode** (transport) layer | ROR secures internode traffic separately; node identity is a different problem. |
| Certificate **issuance, renewal, distribution** | The customer's CA owns this. |
| **Certificate revocation** (CRL/OCSP) | See §4.4 and §6.6 — Elasticsearch does not support it either. |
| Replacing or wrapping the **X-Pack PKI realm** | ROR's ACL is the authority. |
| Client certificates **from ROR to upstream services** (LDAP, external auth) | Already supported. |
| Reading certificates when TLS is terminated **upstream of Elasticsearch** (load balancer, service mesh) | Documented limitation, §3.8 and §6.2. |
| **Certificate fields as `@{...}` runtime variables** in other rules | Deliberately excluded from v1; see §4.3 D7. |
| **Impersonation mocks** for PKI in ROR Kibana | Deferred to a follow-up change; see §4.4. |

---

## 3. Functional requirements

### 3.1 Configuring a PKI provider

A new definitions section — **`pkis`** — sits alongside `ldaps`,
`external_authentication_service_configs`, `user_groups_providers`, `proxy_auth_configs`, `jwt`,
`ror_kbn` and `impersonation`. Each entry defines one **named PKI provider**; ACL rules reference it
by `name`, exactly as they reference an LDAP server.

The provider is deliberately shaped like an `ldaps` entry, with one structural difference. LDAP
configuration splits into two halves:

- **how to find the record** — `search_user_base_DN`, `search_groups_base_DN`, `group_search_filter`
- **how to read it** — `user_id_attribute`, `group_id_attribute`, `unique_member_attribute`

A certificate arrives with the request; there is no directory to search. **A PKI provider is
therefore "LDAP minus the search half"** — the absence of base-DN and filter keys is by design, not
an oversight.

A provider describes:

1. **Identity extraction** (`users:`) — how a ROR username is derived from the certificate.
2. **Group extraction** (`groups:`, optional) — how groups are derived from the certificate.
   When absent, the provider is authentication-only and authorization must come from elsewhere.
3. **Scope constraints** (optional) — `subject_dn_base` and `issuer_dn`, restricting which
   certificates this provider will accept an identity from.

Provider definitions are validated at configuration load time. An invalid provider — malformed
pattern, unknown mode, reference to an undefined provider, or a `pki_authorization` rule pointing at
a provider with no `groups:` section — fails the load with a definitions-level error, consistent
with ROR's treatment of a malformed `ldaps` entry.

### 3.2 Identity and group extraction

Both `users:` and `groups:` follow LDAP's idiom: an optional `mode:` discriminator selecting a
variant, each variant with its own declarative keys, and a default mode requiring no `mode:` key at
all.

**Modes**

| `mode` | Keys | Meaning |
|---|---|---|
| `subject_dn_attribute` *(default)* | `user_id_attribute` / `group_id_attribute` | Read a named attribute out of the subject DN. Defaults: `CN` for users, none for groups (must be given). |
| `subject_dn_pattern` | `pattern` | Apply a regular expression to the subject DN. |
| `san` | `san_type`, optional `pattern` | Read a Subject Alternative Name entry. |

**Attribute mode** parses the DN **structurally**, RDN by RDN — never as a string. Escaping,
attribute ordering and multi-valued RDNs are handled by the parser, so `CN=Smith\, John` correctly
yields `Smith, John`. Attribute names are matched **case-insensitively** (`cn` and `CN` are
equivalent), per the DN specification.

**Pattern mode** operates on the **RFC 2253** rendering of the DN, and this is documented so
patterns can be written reliably. Not the canonical form, which lowercases values and would mangle
usernames. Captured values are **unescaped** after extraction. Patterns must contain exactly one
capture group, must compile, and must not be able to match empty — all validated at config load.

The consequence is deliberate: **the default path has no escaping hazard at all**, and the hazard
exists only for customers who explicitly chose the regex escape hatch.

**Regex semantics differ between users and groups**, and this must be documented: for a username the
pattern matches **once**; for groups it **finds all** matches.

**SAN types**: `dns`, `email`, `uri`, `ip`, and `upn`. The last is shorthand for the `otherName`
entry carrying OID `1.3.6.1.4.1.311.20.2.3`, which is how Active Directory stores the
userPrincipalName. It is included in v1 rather than deferred because in AD the authoritative login
identity is the UPN, not the CN: a certificate with `CN=John Smith` and UPN `jsmith@corp.example.com`
yields an unusable username under CN extraction, and the login name **is not present in the DN at
all**, so no pattern can recover it. Without UPN support the headline enterprise pattern (§3.5, PKI
identity + LDAP groups) is broken for AD shops specifically.

When a certificate carries **several SAN entries of the requested type** — routine for host
certificates, which commonly hold an FQDN, a short name and aliases — the **first** entry is used,
and an optional `pattern` selects and extracts. Rejecting multi-SAN certificates would break
ordinary certificates; requiring a pattern only when more than one is present would make a
configuration valid or invalid depending on which certificate arrives at runtime, which is
undebuggable. More than one entry with no pattern is logged at DEBUG, not WARN — it is the normal
case.

**Multi-valued RDNs** (`CN=svc1,OU=ingest+OU=metrics,O=Corp`, legal and distinct from two separate
RDNs) are treated as **two values**. Parsers disagree here, so the behaviour is pinned rather than
inherited.

**Group ID vs group name.** A certificate carries one string per attribute; there is no second
attribute holding a display name. `group_name_attribute` is therefore **rejected at config load with
an explicit message**, following the precedent already set for LDAP's group-search-in-user-entries
mode, rather than being silently ignored.

**Case normalisation is not a provider concern.** ROR's global `username_case_sensitivity`
(`case_sensitive` / `case_insensitive`) already governs username matching.

### 3.3 Scope constraints

Two optional constraints restrict which certificates a provider will accept an identity from. Both
are post-hoc checks on fields of an already-verified certificate, so they behave identically
regardless of which component terminated TLS.

- **`subject_dn_base`** — the subject DN must **end with** this RDN sequence (a structural suffix
  match, not a string comparison).
- **`issuer_dn`** — the issuer DN must match **exactly** (naming one specific CA).

`subject_dn_base` exists because one corporate CA commonly issues to multiple populations:

```
CN=svc-logstash,OU=Services,DC=corp,DC=example,DC=com     ← machine
CN=John Smith,OU=People,DC=corp,DC=example,DC=com         ← human
```

Both chain to the same CA, so TLS trusts them equally and a plain `CN` extractor authenticates both.
The alternatives — a rule-level `users: ["svc-*"]` pattern, or folding the filter into a
`subject_dn_pattern` regex — make security depend on a CN naming convention the CA may not enforce,
or discard the safe structural extractor and merge extraction with filtering into one expression.

`issuer_dn` covers what `subject_dn_base` cannot: two CAs issuing **byte-identical** subject DNs.
Any CA trusted at node level can mint any name, so where a truststore holds more than one CA,
pinning the issuer is the standard mitigation.

Note that **per-provider truststores are deliberately not supported** — see §4.3 D3.

### 3.4 Client certificate requests: the TLS layer

For a PKI rule to have anything to work with, the TLS layer must request a client certificate.

**X-Pack-terminated (primary path).** Configured entirely in `elasticsearch.yml` via
`xpack.security.http.ssl.client_authentication`, which already accepts `none` / `optional` /
`required`. ROR neither duplicates nor overrides it. Trust anchors likewise come from
`xpack.security.http.ssl.*`.

**ROR-terminated.** `readonlyrest.ssl.client_authentication` is a boolean today, where `true`
configures the TLS layer in *required* mode. Once a third state exists, `true` is genuinely
ambiguous to a reader: comparing two configs, one saying `true` and one saying `optional`, it is
impossible to tell which is stricter. The setting therefore gains named values:

| Documented value | TLS behaviour |
|---|---|
| `none` | No certificate requested. Default. |
| `optional` | Certificate requested; verified if presented; connection proceeds if absent. |
| `required` | Certificate required; handshake fails without a valid one. |

The names match X-Pack's, so operators and migrating customers use one vocabulary. They also avoid a
YAML trap: any mode named `off`, `no`, `yes` or `on` would be coerced to a boolean by YAML 1.1
before the decoder saw it.

Backward compatibility is preserved exactly:

- `false` (or absent) continues to mean `none`; `true` continues to mean `required`.
- Both remain accepted indefinitely but are **absent from the documentation**, which shows only the
  three named values.
- Using a boolean logs a single **INFO** line at settings load suggesting the named equivalent —
  discoverable without being alarming for a configuration that works correctly.
- Validation errors list **only the three documented values**, so the legacy syntax quietly
  disappears from customers' mental models without ever breaking.
- The legacy alias key `verification` stays **boolean-only**. A deprecated key does not learn new
  capabilities; anyone wanting three-state behaviour must move to `client_authentication`.

`optional` is the mode for deployments serving both certificate-bearing services and
password-bearing humans. `required` remains correct where every client holds a certificate — it
simply moves the "no certificate" rejection from the ACL to the handshake.

**Trust verification stays in the TLS layer on both paths.** The ACL never decides whether a chain
is valid; by the time a rule runs, the certificate is known-good. A rule failure can therefore only
ever mean "this valid certificate does not identify a user I recognise", never "this certificate
might be forged".

### 3.5 Authentication and authorization flows

**Authentication.**

1. A client opens a TLS connection presenting a client certificate.
2. The TLS layer — ROR's or X-Pack's — verifies the chain against the configured trust anchors and
   checks validity dates. On failure the handshake aborts; **no request reaches the ACL**.
3. On success the verified certificate is associated with the connection.
4. A request arrives; the ACL evaluates blocks in order.
5. A block containing `pki_authentication` (or `pki_auth`) is reached. The rule reads the verified
   certificate, applies the referenced provider's scope constraints and identity extraction, and
   matches the result against the rule's accepted-users specification if one is given.
6. On success the user identity is set in the block context, exactly as `ldap_authentication` or
   `proxy_auth` would.
7. On failure the rule does not match; evaluation moves to the next block.

The closest existing analogue is **`proxy_auth`**: an identity established outside the ACL and
consumed by it. The difference is that `proxy_auth` must *trust* an upstream component not to spoof
a header, whereas PKI identity is cryptographically verified. PKI is strictly the stronger of the
two, and this belongs in the documentation as a migration argument.

**Authorization** has three sources, in increasing order of external dependency:

**(a) Certificate-derived groups.** Extracted per §3.2, treated as **external groups**, flowing into
ROR's standard group-mapping machinery — simple (same names) or advanced (`local_group` /
`external_group_ids`). No directory required.

**(b) Local `users` section.** The extracted username matches `username` patterns in `users`, and
the user receives the local groups declared there.

**(c) Delegated authorization.** The certificate provides only the username; groups come from
`ldap_authorization`, `groups_provider_authorization`, or another existing authorization rule. This
requires **no new mechanism** — the ACL already supports pairing an authentication rule with a
separate authorization rule, both at block level and inside a `users` entry — and is expected to be
the most common enterprise deployment: identity from the corporate PKI, groups from the corporate
directory.

Three rules, mirroring LDAP:

| Rule | Role | LDAP analogue |
|---|---|---|
| `pki_authentication` | Authentication only | `ldap_authentication` |
| `pki_authorization` | Authorization only (groups from the certificate) | `ldap_authorization` |
| `pki_auth` | Combined | `ldap_auth` |

**Combined flow**, both shapes already expressible in ROR's grammar:

```yaml
# in a block
pki_authentication: { name: "corporate_pki" }
ldap_authorization: { name: "corporate_ldap", groups: ["ops"] }
```

```yaml
# in the users section
- username: "*"
  groups: ["ops", "readers"]
  pki_authentication: "corporate_pki"
  ldap_authorization: "corporate_ldap"
```

### 3.6 Expected behaviour

- A PKI rule is **inert without a certificate**: it does not match, and evaluation continues. It is
  never an outright rejection by itself. This is what makes mixed mode work.
- **Verification is never re-done by the ACL.**
- **One connection, one identity.** The certificate is a property of the TLS connection, so all
  requests multiplexed over a keep-alive connection carry the same identity.
- **PKI identity does not override an explicit credential.** If a request carries both a certificate
  and an `Authorization` header, each rule evaluates only its own credential type; the first
  matching block wins, per normal ACL ordering.
- **Username matching follows existing conventions** — the same pattern semantics as `proxy_auth`'s
  `users` field and `username` in the `users` section, honouring `username_case_sensitivity`.
- **Configuration reload applies to providers.** Changes take effect for new connections; existing
  connections retain the identity established at handshake time. Documented.

### 3.7 Group filtering is intentionally absent

Certificates often carry attributes meaningful to other systems (`OU=Warsaw`, `OU=IT`) that should
not become ROR groups, and a DN's OUs may be a **hierarchical path** rather than a flat set:

```
CN=beats-01,OU=ingest,O=Corp                                          → ["ingest"]
CN=jsmith,OU=Engineering,OU=EMEA,OU=Employees,DC=corp,DC=example,DC=com
                            → ["Engineering", "EMEA", "Employees"]  — only the first is a role
```

DNs read most-specific-first, so the second example's OUs describe a position in an org tree.

No filtering mechanism is provided, because **local group mapping already solves this**: extracted
values are only *external* groups, and become ROR groups only when a `users` entry maps them.
Unmapped values are simply discarded. A filter would be a second mechanism for the same outcome.

What this does require is documentation: OU order and hierarchy are **not interpreted**, and mapping
only the role-bearing attributes is the customer's responsibility.

### 3.8 Failure scenarios

| # | Scenario | Where detected | Behaviour |
|---|---|---|---|
| 1 | No client certificate, client auth `optional` | ACL | Rule does not match; next block. DEBUG log. |
| 2 | No client certificate, client auth `required` | TLS | Handshake fails. Client sees a TLS error, not HTTP 401. |
| 3 | Certificate not chaining to a trusted CA | TLS | Handshake fails. ACL never runs. |
| 4 | Certificate expired or not yet valid | TLS | Handshake fails. |
| 5 | Valid certificate, extraction yields nothing (no CN, pattern does not match) | ACL | Rule does not match. **WARN** — almost always a misconfiguration, and must be easy to find. |
| 6 | Valid certificate, username extracted, rejected by `subject_dn_base` / `issuer_dn` | ACL | Rule does not match; next block. |
| 7 | Valid certificate, username extracted, not accepted by the rule's `users` list | ACL | Rule does not match; next block. |
| 8 | Valid certificate, username extracted, no matching `users` entry | ACL | Authorization fails; next block. |
| 9 | `pki_authorization` / `pki_auth` referencing a provider with no `groups:` | Config load | Rejected, definitions-level error. |
| 10 | `group_name_attribute` used in a `pkis` entry | Config load | Rejected with an explicit message. |
| 11 | Rule references an undefined provider name | Config load | Rejected. |
| 12 | Malformed `pattern` (uncompilable, wrong capture-group count, can match empty) | Config load | Rejected. |
| 13 | Certificate revoked | **Not detected** | Out of scope; see §6.6. |
| 14 | TLS terminated **upstream of Elasticsearch** (LB, service mesh) | ACL | No certificate available; PKI rules never match. Must be documented prominently — the most likely support case. |
| 15 | `pkis` configured but client authentication is `none` / disabled | Config load | **WARN** — always a mistake; PKI rules can never match. |

Scenarios 2–4 deserve documentation beyond correctness: TLS-layer failures reach the client as
opaque connection errors and cannot carry an ROR message. Operators debugging "my Beats agent can't
connect" must be told to check handshake behaviour before ACL logs.

### 3.9 Backward compatibility requirements

- A configuration with no `pkis` section and no `pki_*` rule behaves **exactly** as before.
- `client_authentication: true` / `verification: true` keep meaning *required*; `false`/absent keep
  meaning *no certificate requested*.
- Named client-authentication values are reachable only by writing them explicitly.
- Existing rules, definitions sections, and the ACL evaluation model are unchanged.
- No audit event changes (§4.4).

---

## 4. High-level design

### 4.1 The architectural problem

ROR's TLS layer and its ACL are separated by an Elasticsearch-shaped gap.

TLS is terminated either by ROR's own HTTP server transport or by Elasticsearch with X-Pack Security
enabled. The ACL runs much later, inside an Elasticsearch action filter, against a request
abstraction built from the ES REST request. Between those two points sits Elasticsearch's request
pipeline, which carries no ROR-specific state.

The verified peer certificate exists at the first point and is unavailable at the second. **Bridging
that gap is the central architectural task**; everything else is a conventional addition of a
definitions section and three rules following patterns ROR already uses.

Fortunately the bridge has a natural anchor: ROR already reaches the HTTP channel when building its
request context, in order to derive local and remote addresses. That is the point at which the peer
certificate can be obtained — and it works regardless of which component terminated TLS.

### 4.2 Components and responsibilities

```
   ┌──────────────────────────────┐    ┌──────────────────────────────┐
   │  TLS termination — X-Pack    │ OR │  TLS termination — ROR SSL   │
   │  (primary path)              │    │                              │
   │                              │    │                              │
   │  xpack.security.http.ssl.*   │    │  readonlyrest.ssl.*          │
   │  client_authentication:      │    │  client_authentication:      │
   │    none|optional|required    │    │    none|optional|required    │
   └──────────────┬───────────────┘    └───────────────┬──────────────┘
                  │                                    │
                  └────────────────┬───────────────────┘
                                   │
     RESPONSIBILITY (either): decide whether a certificate is TRUSTWORTHY.
     Verify the chain, check validity dates. Never decide WHO it is.
                                   │
                                   │  verified peer certificate,
                                   │  bound to the connection
                                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  Peer-identity propagation                                         │
   │                                                                    │
   │  • obtains the verified certificate from the connection at the     │
   │    point where ROR builds its request context                      │
   │  • must be spoof-proof: not derivable from anything a client can   │
   │    send, and not confusable with a client-supplied header          │
   │  • absent for non-TLS or non-certificate connections               │
   │                                                                    │
   │  RESPONSIBILITY: make the certificate VISIBLE to the ACL,          │
   │  intact and unforgeable.                                           │
   └───────────────────────────────┬────────────────────────────────────┘
                                   │  request context, optionally
                                   │  carrying a verified certificate
                                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  ACL — pki_authentication / pki_authorization / pki_auth           │
   │                                                                    │
   │  • read the verified certificate from the request context          │
   │  • apply the provider's scope constraints                          │
   │  • apply identity extraction → username                            │
   │  • optionally apply group extraction → external groups             │
   │                                                                    │
   │  RESPONSIBILITY: decide WHO the certificate identifies, and        │
   │  whether that identity satisfies this rule.                        │
   └───────────────────────────────┬────────────────────────────────────┘
                                   │  authenticated user (+ groups)
                                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  Existing ACL machinery — unchanged                                │
   │  users section · group mapping · groups rules · indices · kibana   │
   │  fields · filter · audit                                           │
   └────────────────────────────────────────────────────────────────────┘
```

The **PKI provider definition** (`pkis` entry) binds this together: scope constraints, identity
extraction, optional group extraction. Rules reference it by name — the role an `ldaps` entry plays
for LDAP rules, and the symmetry should extend to error messages.

### 4.3 Key design decisions

**D1 — Trust validation stays in the TLS layer; the ACL only interprets identity.**
The ACL is not a certificate validator. One code path for chain validation, no "valid at layer A,
invalid at layer B" bugs, and a simple invariant for rules: *any certificate a rule sees has already
been verified*.

**D2 — Propagation must be unforgeable.**
The cheap implementation — inject the subject DN as an HTTP header — is **explicitly rejected**. A
client-supplied header of the same name would be indistinguishable from an ROR-supplied one, turning
the feature's strongest property (cryptographic proof of identity) into its weakest: a
trusted-header scheme with none of `proxy_auth`'s deployment caveats documented. The propagation
channel must be one a client cannot write to. The mechanism is an implementation choice; the
requirement is architectural and non-negotiable.

**D3 — No per-provider truststores.**
Superficially attractive for separating trust domains, but it cannot behave the same on both TLS
paths. On the ROR path we own the SSL context and could add a second trust manager. On the X-Pack
path ES has already completed the handshake using its own truststore, so honouring a per-provider
truststore would mean running a **second, parallel PKIX validation** with different anchors, capable
of disagreeing with the one ES just ran. The result would be identical YAML producing different
security outcomes depending on which component terminates TLS — silently. `subject_dn_base` and
`issuer_dn` (§3.3) give the same separation as pure post-hoc checks that behave identically
everywhere.

**D4 — Identity extraction is provider configuration, not rule configuration.**
Certificate layout is a property of the issuing CA, not of an individual rule. A customer states
"our CA puts the service name in the CN" once, and every rule inherits it — mirroring LDAP, where
`user_id_attribute` lives on the server definition.

**D5 — Extraction is a declarative spec, not a variable expression.**
An earlier draft proposed expressing extraction with ROR's `@{...}` / `#{...}` variable and
transformation syntax. That is a category error: `@{...}` means "substitute this value into a
template", whereas this configuration must mean "here is *how* to extract". LDAP's
`user_id_attribute: "uid"` reads correctly because it names a source. The `mode:`-discriminator
idiom in §3.2 follows LDAP's house style, and the transformation DSL's main practical benefit here
— case normalisation — is already covered by `username_case_sensitivity`.

**D6 — Three rules, mirroring LDAP.**
Not one rule with modes. Users already understand the
`ldap_authentication` / `ldap_authorization` / `ldap_auth` triad, and PKI has the same shape.

**D7 — No `@{cert:...}` runtime variables in v1.**
Exposing certificate fields for interpolation elsewhere (`indices: ["logs-@{cert:common_name}-*"]`)
is genuinely useful and a legitimate place for the transformation DSL — but it is a second feature
with its own surface, and the value is available before authentication has run. Excluded from v1;
noted as future work.

**D8 — A missing certificate is a non-match, not a rejection.**
Rules that reject rather than decline break ACL composition and make mixed mode impossible.

**D9 — Certificate-derived groups are "external groups".**
Feeding them into existing group mapping rather than treating them as local groups means advanced
mapping, group renaming, and the `groups`/`groups_and`/`groups_or` rules all work unmodified, and
PKI behaves consistently with LDAP and `user_groups_providers`.

### 4.4 Cross-cutting concerns

**Audit — no changes.** The extracted username flows into the existing `user` / `presented_identity`
fields through machinery that already works, and `block` / `matched_block_names` already identify
which rule matched. No certificate metadata is recorded, no new audit field group, no serializer
changes. One documented limitation: audit cannot distinguish two certificates sharing a CN (a
reissued certificate, or the same CN from two CAs). Accepted.

**Impersonation — as `proxy_auth`.** ROR requires each authentication rule to answer "does this user
exist?". PKI has no user store to query, only a certificate present on this connection or not, so
the answer is *cannot check* — precisely what `proxy_auth` does. This needs **no ROR Kibana change**:
KBN's impersonation is username-based and rule-agnostic, it sends a generic impersonation header, and
the local-users API already models an "unknown users" flag for exactly the unbounded-population case
a wildcard PKI `users` entry produces.

**Impersonation mocks — deferred.** ROR Kibana's impersonate UI has *per-service-type* panels (LDAP,
external authentication, external authorization, local users). A PKI mock would need a new panel, so
it is out of scope here and will be a separate change.

**Local users enumeration.** A PKI provider cannot enumerate its users; like LDAP with a wildcard,
the population is unbounded. PKI-based `users` entries with wildcard usernames contribute
"unknown/unbounded" rather than a concrete list.

**Caching — none.** Identity extraction is a local, cheap, deterministic operation on data already
in memory. Where authorization is delegated, those providers' existing `cache_ttl` mechanisms apply
unchanged.

**Revocation — out of scope; if ever built, it belongs at the ACL layer.** Elasticsearch itself has
no CRL/OCSP support, so ROR is at parity (§6.6). Should it ever be built, note that on the primary
path the handshake is long finished before ROR sees the certificate — so revocation checking cannot
live in the TLS layer and would have to become an explicit exception to D1.

**FIPS.** PKI adds no new cryptographic operations beyond what the handshake already performs;
identity extraction reads already-parsed certificate fields. Compatibility is expected to be
unaffected but must be verified. BouncyCastle is already a `core` dependency, which makes the DER
decoding required for `otherName`/UPN a small job rather than a new dependency.

---

## 5. Configuration examples

### 5.1 Minimal — ID-less service authentication (the PoC shape)

Certificates carry the service name in the CN; authorization comes from the rule itself. This is
close to what the driving customer needs, and their SSL configuration is essentially untouched —
the trust anchors are already in place; only identity extraction is new.

```yaml
readonlyrest:

  ssl:
    enable: true
    keystore_file: "keystore.jks"
    keystore_pass: "readonlyrest"
    client_authentication: required
    truststore_file: "truststore.jks"          # the corporate CA — already configured
    truststore_pass: "readonlyrest"

  access_control_rules:

    - name: "Logstash ingest"
      pki_authentication:
        name: "corporate_pki"
        users: ["logstash-*"]
      actions: ["indices:data/write/*", "indices:admin/template/*"]
      indices: ["ingest-*"]

    - name: "Beats shippers write only to their own index"
      pki_authentication:
        name: "corporate_pki"
      actions: ["indices:data/write/*"]
      indices: ["logs-@{user}-*"]

  pkis:
    - name: corporate_pki
      users:
        user_id_attribute: "CN"                # default; shown for clarity
```

### 5.2 Mixed mode — services by certificate, humans by LDAP, one port

```yaml
readonlyrest:

  ssl:
    enable: true
    keystore_file: "keystore.jks"
    keystore_pass: "readonlyrest"
    client_authentication: optional            # certificate requested, not demanded
    truststore_file: "truststore.jks"
    truststore_pass: "readonlyrest"

  access_control_rules:

    # Certificate-bearing clients match first; clients without one fall through.
    - name: "Machine identities"
      pki_auth:
        name: "service_pki"
        groups: ["ingest_services"]
      indices: ["logs-*", "metrics-*"]

    - name: "Analysts"
      ldap_auth:
        name: "corporate_ldap"
        groups: ["analysts"]
      kibana:
        access: ro
      indices: ["logs-*"]

  pkis:
    - name: service_pki
      users:
        user_id_attribute: "CN"
      groups:
        group_id_attribute: "OU"

  ldaps:
    - name: corporate_ldap
      host: "ldap.example.com"
      port: 636
      bind_dn: "cn=ror,ou=svc,dc=example,dc=com"
      bind_password: "..."
      users:
        search_user_base_DN: "ou=People,dc=example,dc=com"
      groups:
        search_groups_base_DN: "ou=Groups,dc=example,dc=com"
```

### 5.3 The enterprise pattern — identity from PKI, groups from LDAP

The expected majority deployment: the certificate proves *who*, the directory decides *what*.
Note X-Pack terminating TLS — the recommended configuration for new installations.

```yaml
# elasticsearch.yml
xpack.security.enabled: true
xpack.security.http.ssl.enabled: true
xpack.security.http.ssl.keystore.path: "keystore.p12"
xpack.security.http.ssl.client_authentication: optional
xpack.security.http.ssl.truststore.path: "corporate-ca.p12"
```

```yaml
# readonlyrest.yml
readonlyrest:

  access_control_rules:

    - name: "Platform services — full ingest"
      pki_authentication:
        name: "corporate_pki"
      ldap_authorization:
        name: "corporate_ldap"
        groups: ["platform_services"]
      indices: ["*"]

    - name: "Partner services — read only, scoped"
      pki_authentication:
        name: "corporate_pki"
      ldap_authorization:
        name: "corporate_ldap"
        groups_and: ["partners", "readers"]
      actions: ["indices:data/read/*"]
      indices: ["shared-*"]

  pkis:
    - name: corporate_pki
      users:
        user_id_attribute: "CN"

  ldaps:
    - name: corporate_ldap
      host: "ldap.example.com"
      port: 636
      bind_dn: "cn=ror,ou=svc,dc=example,dc=com"
      bind_password: "..."
      cache_ttl: 60s
      users:
        search_user_base_DN: "ou=Services,dc=example,dc=com"
        user_id_attribute: "cn"
      groups:
        search_groups_base_DN: "ou=Groups,dc=example,dc=com"
```

### 5.4 Separating populations issued by one CA

```yaml
  pkis:
    - name: services_pki
      users:
        user_id_attribute: "CN"
        subject_dn_base: "OU=Services,DC=corp,DC=example,DC=com"
        issuer_dn: "CN=Corp Issuing CA,DC=corp,DC=example,DC=com"

    - name: employees_pki
      users:
        user_id_attribute: "CN"
        subject_dn_base: "OU=People,DC=corp,DC=example,DC=com"
```

```yaml
  access_control_rules:

    - name: "Machine identities"
      pki_authentication: { name: "services_pki" }
      indices: ["logs-*"]

    - name: "Employees"
      pki_authentication: { name: "employees_pki" }
      ldap_authorization: { name: "corporate_ldap", groups: ["analysts"] }
```

### 5.5 Active Directory — identity from the UPN

Where the CN is a display name and the login identity is the userPrincipalName.

```yaml
  pkis:
    - name: ad_pki
      users:
        mode: san
        san_type: upn                          # otherName OID 1.3.6.1.4.1.311.20.2.3
```

### 5.6 `users` section with certificate-derived groups and advanced mapping

Air-gapped cluster, no directory. Groups live in OU values and are renamed to ROR local groups.

```yaml
readonlyrest:

  access_control_rules:

    - name: "Ingest tier"
      groups: ["ingest"]
      indices: ["logs-*"]
      actions: ["indices:data/write/*"]

    - name: "Query tier"
      groups: ["query"]
      indices: ["logs-*"]
      actions: ["indices:data/read/*"]

  users:
    - username: "*"
      groups:
        - local_group: "ingest"
          external_group_ids: ["svc-ingest", "svc-beats"]
        - local_group: "query"
          external_group_ids: ["svc-dashboard", "svc-report"]
      pki_auth: "internal_pki"

  pkis:
    - name: internal_pki
      users:
        user_id_attribute: "CN"
      groups:
        group_id_attribute: "OU"
```

### 5.7 Host certificates — identity from a DNS SAN

```yaml
  pkis:
    - name: host_pki
      users:
        mode: san
        san_type: dns
        pattern: "^(.+)\\.corp\\.example\\.com$"   # optional; selects among several DNS SANs
```

### 5.8 Non-standard DN layout — the regex escape hatch

```yaml
  pkis:
    - name: legacy_pki
      users:
        mode: subject_dn_pattern
        pattern: "^CN=([^,]+),OU=Service Accounts,.*$"
      groups:
        mode: subject_dn_pattern
        pattern: "OU=grp-([^,]+)"                  # find-all: every match becomes a group
```

### 5.9 Kibana authenticating to ES by certificate

```yaml
  access_control_rules:

    - name: "Kibana server (certificate, no password)"
      verbosity: error
      pki_authentication:
        name: "corporate_pki"
        users: ["kibana-server"]
      kibana:
        access: admin

    - name: "Kibana users via ROR Kibana plugin"
      ldap_auth:
        name: "corporate_ldap"
        groups: ["analysts"]
      kibana:
        access: ro
```

---

## 6. Compatibility

### 6.1 Backward compatibility

The feature is **purely additive**. Configurations with no `pkis` section and no `pki_*` rule behave
identically before and after:

- No new required keys; no change to the meaning of any existing key.
- `client_authentication: true` / `verification: true` still mean *required*; `false`/absent still
  mean *no certificate requested*.
- Named values are opt-in; `verification` remains boolean-only.
- No change to ACL evaluation semantics, rule ordering, or block matching.
- No change to audit event shape.

One behaviour changes internally for everyone: the transport layer will carry peer certificate state
where it previously discarded it. This must be verified inert when no PKI provider is configured —
no measurable overhead on the non-mTLS path, no change to memory retention on high-connection-count
clusters.

### 6.2 Migration impact

| Starting point | Impact |
|---|---|
| No SSL, or SSL without client authentication | None. Adopting PKI is a fresh configuration. |
| Client authentication used as a connection gate, with passwords for identity | No forced change. Certificates can be adopted incrementally: add `pkis` and a `pki_authentication` block *above* the existing password blocks; clients migrate one at a time; password blocks are removed when the last client has moved. **No flag day.** |
| Migrating from the X-Pack PKI realm | Conceptually close. X-Pack derives a username from the subject DN via `username_pattern` and resolves roles from role mappings; ROR derives one via `mode`/`user_id_attribute`/`pattern` and resolves groups from `users`, LDAP, or the certificate. A translation table belongs in the documentation, including how a `username_pattern` regex maps onto `mode: subject_dn_pattern`. |
| TLS terminated at a load balancer or service mesh | **Not migratable without an architecture change.** ROR cannot see a certificate it did not terminate and Elasticsearch did not receive. These customers must move TLS termination to Elasticsearch/ROR, or continue with `proxy_auth`. State this early and prominently — it is the single most likely source of "the feature doesn't work" tickets. |

### 6.3 Interaction with SSL configuration

- PKI applies to the **external HTTP** SSL layer only. Internode SSL is unaffected.
- **Both terminators are supported.** X-Pack-terminated is the primary path for new deployments;
  ROR-terminated remains fully supported and is what much of the installed base — including the
  customer driving this work — runs today.
- ROR SSL and X-Pack Security remain **mutually exclusive at boot**: ROR refuses to start when
  X-Pack Security is enabled and ROR SSL settings are present. PKI does not change this.
- Trust anchors belong to whichever component terminates TLS. ROR neither duplicates nor overrides
  them, and per-provider truststores are not supported (D3).
- If SSL is disabled, or client authentication is `none`, PKI rules can never match. The loader
  **warns** rather than accepting silently; it is always a mistake.
- FIPS mode must continue to work (§4.4).

### 6.4 Interaction with LDAP rules

Complementary by design, and the intended primary deployment (§5.3): PKI answers *who*, LDAP answers
*what*. No conflict exists, because the ACL already models authentication and authorization as
separable.

The one thing to get right is the **username contract**: the username extracted from the certificate
must be the value LDAP can look up. If the CA puts a display name in the CN but LDAP keys on `uid`
or `sAMAccountName`, the pairing fails at the LDAP search with a confusing "user not found". This is
the most likely misconfiguration in the whole feature and the documentation should lead with it.
The extraction modes — and `san_type: upn` in particular — exist largely to make this contract
satisfiable without re-issuing certificates.

### 6.5 Interaction with proxy authentication

`proxy_auth` and PKI solve the same problem from opposite ends of a trust spectrum:

| | `proxy_auth` | PKI |
|---|---|---|
| Identity source | HTTP header | TLS client certificate |
| Trust basis | Operator's assertion that the header cannot be spoofed | Cryptographic verification |
| Deployment care required | High — the ES port must be unreachable except through the proxy | Low — the certificate is self-authenticating |
| TLS termination | Typically upstream | Must be at Elasticsearch/ROR |

They can coexist in one configuration, in separate blocks — a legitimate transitional architecture.
**PKI should be documented as preferred wherever both are viable**, because it removes
`proxy_auth`'s hard deployment requirement.

D2 matters here: propagating certificate identity through a header would collapse PKI into
`proxy_auth`, silently inheriting its deployment caveat while presenting itself as cryptographically
strong. That is a security regression disguised as a feature.

### 6.6 Certificate revocation

**Out of scope, and at parity with Elasticsearch.** Elasticsearch has no CRL or OCSP support for
TLS: no such setting exists in the security settings reference, and Elastic's stated position on the
PKI realm is that it is not supported. A revoked-but-unexpired certificate is accepted.

This is defensible for a specific reason worth stating in customer-facing documentation: **the ACL
is itself the deprovisioning mechanism.** To kill a compromised identity, remove it from the `users`
section or from a rule's `users` list and reload — seconds, no CA involvement, no CRL propagation
delay. X-Pack PKI users have no equivalent.

It is worth recording that this is a **differentiator, not a parity item**: Search Guard and
OpenSearch Security both ship CRL/OCSP support, so if it arises competitively it will be against
them rather than against Elastic.

---

## 7. Decisions and remaining open questions

### 7.1 Decisions taken

| Area | Decision |
|---|---|
| TLS terminators | Both supported. X-Pack primary for new deployments; ROR SSL fully supported. |
| Certificate access | Obtained from the connection at the point where ROR builds its request context; never via an HTTP header (D2). |
| Section name | `pkis` — chosen for X-Pack vocabulary familiarity, since PKI-realm migration is an explicit use case. |
| Rule names | `pki_authentication`, `pki_authorization`, `pki_auth`. |
| Extraction config | Declarative spec, LDAP-shaped (`mode:` discriminator + attribute keys). Not variable/transformation syntax (D5). |
| Scope constraints | `subject_dn_base` (suffix match) and `issuer_dn` (exact match), both in v1. |
| Per-provider truststore | Not supported (D3). |
| Groups from certificates | In v1; all three rules ship together. |
| Group filtering | Not provided — local group mapping covers it (§3.7). |
| `group_name_attribute` | Rejected at config load with an explicit message. |
| Multi-valued RDNs | Treated as multiple values. |
| SAN types | `dns`, `email`, `uri`, `ip`, `upn`. Multiple entries → first, `pattern` to select. |
| DN handling | Attribute mode parses structurally; pattern mode uses RFC 2253 and unescapes captures. |
| Attribute name matching | Case-insensitive. |
| `client_authentication` (ROR SSL) | `none` / `optional` / `required` documented; booleans accepted but undocumented; INFO log on legacy use; `verification` frozen boolean-only. |
| Revocation | Out of scope; ACL-level deny is the documented deprovisioning story. |
| Audit | Username only; no new fields, groups, or serializers. |
| Impersonation | As `proxy_auth` (cannot-check). No ROR Kibana change required. |
| Impersonation mocks | Deferred to a separate change. |
| `@{cert:...}` variables | Not in v1 (D7). |
| Delivery | Implement on `es94x` first; port to remaining ES modules after review. |

### 7.2 Remaining open questions

**Licensing tier.** Paid features currently live in ROR Kibana, which suggests an ES-side ACL rule
should be free — the working assumption. Worth noting that X-Pack's PKI realm requires Platinum, so
gating would not be unusual in the market. **If** it is ever gated, decide the *enforcement point*
as well as the tier: rejecting the `pkis` section at config load and having rules silently never
match give very different customer experiences, and this repository has prior instances of premium
functionality shipping under-gated.

**`san_type: upn` naming.** `upn` is proposed as shorthand for the `otherName` entry with OID
`1.3.6.1.4.1.311.20.2.3`. Whether to also expose a general `other_name` mode taking an arbitrary OID
is undecided; no known requirement yet.

**Documentation translation table for X-Pack migrations.** Mapping `username_pattern` regexes onto
`mode: subject_dn_pattern` is mechanical but needs writing, and is the main thing a migrating
customer will look for.

---

## Appendix A — Current behaviour this RFC builds on

Verified against the repository at the time of writing, as orientation for reviewers:

| Concern | Where it lives today |
|---|---|
| External HTTPS SSL settings, incl. `client_authentication` / `verification` | `readonlyrest.ssl` section; ROR's SSL settings model |
| Client auth configured in **required** mode when enabled | ROR's SSL context preparation helper |
| ROR SSL and X-Pack Security mutually exclusive at boot | ROR SSL settings loader |
| Request abstraction seen by the ACL — method, path, headers, addresses, content; **no peer certificate** | ROR's REST request abstraction |
| HTTP channel already reachable when ROR builds its request context | ROR's REST channel wrapper |
| Definitions sections | `ldaps`, `proxy_auth_configs`, `external_authentication_service_configs`, `user_groups_providers`, `jwt`, `ror_kbn`, `impersonation`, `variables_function_aliases` |
| Authentication/authorization/combined rule triad | `ldap_authentication`, `ldap_authorization`, `ldap_auth` |
| `mode:` discriminator selecting a variant with its own key set | `ldaps` → `groups` → `mode` |
| Rejecting an unsupported key with an explicit message | `ldaps` group-search-in-user-entries mode rejecting `group_name_attribute` |
| Identity-from-outside-the-ACL rule, cannot-check user existence | `proxy_auth` (+ `proxy_auth_configs`) |
| Group mapping, simple and advanced (`local_group` / `external_group_ids`) | `users` section |
| Global username case handling | `global_settings.username_case_sensitivity` |
| Audit fields incl. `user`, `presented_identity`, `block`, `matched_block_names`; field groups per serializer | audit module serialization helper |
| Impersonation: generic username header, rule-agnostic; unknown-users flag already modelled | ROR Kibana proxy + local-users API |
| Per-service-type impersonation mock panels (LDAP / authn / authz / local users) | ROR Kibana impersonate UI |
| BouncyCastle (FIPS) available for DER decoding | `core` dependencies |
