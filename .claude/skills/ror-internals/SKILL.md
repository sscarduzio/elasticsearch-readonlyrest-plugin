---
name: ror-internals
description: ReadonlyREST internal architecture knowledge — kibana_access rule semantics and decision tree, FLS engine strategies, correlation-ID header contract, ROR admin API endpoints and their ES action names. Use when coding or reviewing changes to the kibana rules, FLS/fields rule, audit logging, or the _readonlyrest admin/metadata APIs.
---

# ROR Internals (ES plugin)

Distilled from `beshu-tech/readonlyrest-internal/architecture/` and `api/`. Treat as design intent; the code is authority (update this on discrepancy).

## kibana_access rule

Code: `core/.../blocks/rules/kibana/KibanaAccessRule.scala`; action groups in `Constants.java` (`CLUSTER_ACTIONS`, `ADMIN_ACTIONS`, `RW_ACTIONS`, `RO_ACTIONS`).

- Omitting `kibana_access` ≡ `unrestricted`. The explicit `unrestricted` value exists so YAML anchor/template blocks can **override** a templated `rw` default.
- Design intent of all restricted levels: the kibana index is writable (saved objects must work), all other "data indices" are **read-only** — protect against accidental data loss. Special-case writable indices exist (e.g. reporting).
- `admin` vs `rw` differs ONLY in access to ROR settings/activation-keys APIs (`cluster:ror/*`).

| level | kibana_index | data indices | reporting index | cluster mgmt | ROR settings API |
|-----------|-----------|-----------|------|-----------|------|
| admin | full | read only | full | read only | full |
| rw | full | read only | full | read only | none |
| ro | full | read only | full | none | none |
| ro_strict | read only | read only | none | none | none |

Decision tree (simplified): unrestricted → ALLOW; user-metadata API (login) → ALLOW; RO_ACTIONS or CLUSTER_ACTIONS → ALLOW; no indices involved → ALLOW; Kibana sample-data creation → ALLOW; index is kibana_index and level ≠ ro_strict → ALLOW; RO/RW action targeting kibana_index → ALLOW; action starts with `indices:data/write` → REJECT; level=admin and action in ADMIN_ACTIONS → ALLOW; else REJECT. **Changing the order of these checks is a behavioral change** — review accordingly.

## FLS (fields rule) engines

Code: `FieldsFiltering.scala` (uses `XContentMapValues.filter`, same method as ES source filtering); strategy resolution in the fields rule.

- Engines: `es_with_lucene` (default — ES handles, lucene fallback), `es` (no fallback), and a **hidden, undocumented `lucene` engine**: full old-style lucene handling, kept as an emergency escape hatch when the ES-level path has a bug. Never remove it; never document it publicly.
- Request field usage trifurcation (`RequestFieldsUsage`): `NotUsingFields` (best case), `UsingFields` (extractable → not-allowed fields get obfuscated, e.g. `field1` → `field1_ROR_12Xb593a24`), `CannotExtractFields` (query_string, function_score, wildcards, scripts — no way to enumerate fields → must fall back to lucene).
- Resolved strategies: `FlsAtLuceneLevelApproach` (thread-context header set per ES module), `BasedOnBlockContextOnly.EverythingAllowed`, `BasedOnBlockContextOnly.NotAllowedFieldsUsed` (query rewrite via `QueryWithModifiableFields` in each ES module). The resolved strategy is logged under `fls`.
- Lucene-level FLS requires ROR on **all** nodes and is slow; ES-level does not. Keep this constraint in mind when reviewing FLS changes.

## Correlation ID contract

ROR ES is session-unaware; `x-ror-correlation-id` request header correlates requests. If absent, ROR generates a UUID. The metadata endpoint (`/_readonlyrest/metadata/current_user`) echoes the effective ID back as `x-ror-logging-id`. The ID goes into the audit log. Don't break this contract — Kibana relies on it for session-scoped log correlation.

## ROR API endpoints ↔ ES actions

Every ROR REST endpoint maps to a dedicated ES action name (used in ACL `actions` rule and audit). Keep the mapping stable:

| Endpoint | Action |
|---|---|
| `GET /_readonlyrest/metadata/current_user` | `cluster:ror/user_metadata/get` |
| `GET/POST /_readonlyrest/admin/config` (+ `/file`, `/refreshconfig`) | `cluster:ror/config/refreshsettings` (legacy), `cluster:ror/config/manage` (cluster summary) |
| `POST/DELETE /_readonlyrest/admin/config/test` | `cluster:ror/testconfig/manage`; in-memory test config, TTL via `x-ror-test-settings-ttl` (default 30 min) |
| `POST/DELETE /_readonlyrest/admin/authmock` | `cluster:ror/authmock/manage`; TTL via `x-ror-auth-mock-ttl` (default 30 min) |
| `POST /_readonlyrest/admin/audit/event` | `cluster:ror/audit_event/put` |

Behavioral invariants:

- **Audit event API**: body ≤ 5KB (413 otherwise); request is always audited, but custom fields are added only if the request is ALLOWED; custom fields must **never overwrite** default serializer fields.
- **Auth mocks** exist to support impersonation (`IMPERSONATE_AS` header) for rules backed by external services (LDAP, external authn/authz) — ROR can't query the real service with the impersonated user's credentials, so it answers from the mock. Mocks always carry a TTL; manual DELETE is optional.
- **Cluster config summary** (`cluster:ror/config/manage`): the handling node propagates to all nodes and compares each response against its own config; any difference is a warning (`NODE_RETURNED_CONFIG_ERROR`, `NODE_FORCED_FILE_CONFIG`, ...); an error loading the **current** node's config fails the whole request (nothing to compare against).
- 403 body shape for all endpoints is the standard ROR forbidden JSON (`"reason":"forbidden"`); don't invent new error shapes.
