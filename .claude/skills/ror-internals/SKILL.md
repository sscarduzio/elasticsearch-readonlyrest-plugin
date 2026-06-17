---
name: ror-internals
description: ReadonlyREST internal architecture knowledge — kibana rule semantics and decision tree (BaseKibanaRule.shouldMatch, shared by the kibana and legacy kibana_access rules), FLS engine strategies, correlation-ID header contract, ROR admin API endpoints and their ES action names. Use when coding or reviewing changes to the kibana rules, FLS/fields rule, audit logging, or the _readonlyrest admin/metadata APIs.
---

# ROR Internals (ES plugin)

Distilled from `beshu-tech/readonlyrest-internal/architecture/` and `api/`. Treat as design intent; the code is authority (update this on discrepancy).

## kibana rule

Shared decision tree (`shouldMatch`): `core/.../blocks/rules/kibana/BaseKibanaRule.scala`. Two concrete rules extend it:
- **Current**: `KibanaUserDataRule.scala` — YAML name `kibana`.
- **Legacy**: `KibanaAccessRule.scala` — YAML name `kibana_access`, `@deprecated` since `1.48.0`.

Action groups are Scala `PatternsMatcher` vals in `KibanaActionMatchers.scala` (`roActionPatternsMatcher`, `rwActionPatternsMatcher`, `clusterActionPatternsMatcher`, `adminActionPatternsMatcher`, `nonStrictActions`, `indicesWriteAction`) — there is no `Constants.java`.

- Omitting `kibana_access` ≡ `unrestricted`. The explicit `unrestricted` value exists so YAML anchor/template blocks can **override** a templated `rw` default.
- Design intent of all restricted levels: the kibana index is writable (saved objects must work), all other "data indices" are **read-only** — protect against accidental data loss. Special-case writable indices exist (e.g. reporting).
- `admin` grants `rw`-level kibana access **plus** the full `adminActionPatternsMatcher` set on no-index requests and the ROR config index / index-management / tags paths (step 9) — that includes `indices:admin/create`, `indices:admin/ilm/*`, `cluster:admin/ingest/pipeline/put`, etc., none of which `rw` can reach. It also gates the ROR settings/activation-keys APIs.

| level | kibana_index | data indices | reporting index | cluster mgmt | ROR settings API |
|-----------|-----------|-----------|------|-----------|------|
| admin | full | read only | full | read only | full |
| rw | full | read only | full | read only | none |
| ro | non-strict only¹ | read only | full | none | none |
| ro_strict | read only | read only | none | none | none |
| api_only² | read only | read only | none | none | none |

¹ `ro` cannot modify the kibana index in general (`kibanaCanBeModified = false`). It only permits a narrow allow-list of saved-object writes via `isRoNonStrictCase` (step 8) — discover UI state, short URLs, index-pattern/url/config doc updates. It is not full kibana-index write access.

² `api_only` (YAML value `api_only`) is read-only on the kibana index like `ro_strict`, but its semantics are distinct: it couples to `allowed_api_paths` (`KibanaUserDataRuleDecoder.scala`) to gate which ROR/ES API paths the user may call. It is its own `KibanaAccess` value, not an alias of `ro`.

Decision tree — `shouldMatch` is a short-circuit OR chain (first match → ALLOW; if none match → REJECT). Order below mirrors `BaseKibanaRule.shouldMatch` exactly:

1. `isUnrestrictedAccessConfigured` — access level is `unrestricted`.
2. `isUserMetadataRequest` — ROR user-metadata path (login).
3. `isDevNullKibanaRelated` — request targets the internal `.kibana-devnull` index.
4. `isRoAction` — action matches `roActionPatternsMatcher`.
5. `isClusterAction` — action matches `clusterActionPatternsMatcher`.
6. `emptyIndicesMatch` — request has **no indices** AND ((kibana writable AND RW action) OR (admin access AND admin action)). Not a blanket allow.
7. `isKibanaSimpleData` — kibana writable AND request is a Kibana sample-data index/stream.
8. `isRoNonStrictCase` — targets kibana index, level ≠ `ro_strict`, kibana not modifiable, non-strict allowed path AND non-strict action (saves discover UI state / short URLs).
9. `isAdminAccessEligible` — admin access configured AND admin action AND request allowed for admin (no-index, ROR index, index-management or tags path).
10. `isKibanaIndexRequest` — kibana writable AND targeting kibana index AND (RO OR RW OR `indices:data/write/*` action).

**Changing the order of these checks is a behavioral change** — review accordingly.

Note: `indices:data/write/*` is **not** blanket-rejected — it's allowed via step 10 (writable kibana index) and is also a member of `adminActionPatternsMatcher` (step 9).

## FLS (fields rule) engines

Code: `FieldsFiltering.scala` (uses `XContentMapValues.filter`, same method as ES source filtering); strategy resolution in the fields rule.

- Engines: `es_with_lucene` (default — ES handles, lucene fallback), `es` (no fallback), and a **hidden, undocumented `lucene` engine**: full old-style lucene handling, kept as an emergency escape hatch when the ES-level path has a bug. Never remove it; never document it publicly.
- Request field usage trifurcation (`RequestFieldsUsage`): `NotUsingFields` (best case), `UsingFields` (extractable → not-allowed fields get obfuscated, e.g. `field1` → `field1_ROR_12Xb593a24`), `CannotExtractFields` (query_string, function_score, wildcards, scripts — no way to enumerate fields → must fall back to lucene).
- Resolved strategies: `FlsAtLuceneLevelApproach` (thread-context header set per ES module), `BasedOnBlockContextOnly.EverythingAllowed`, `BasedOnBlockContextOnly.NotAllowedFieldsUsed` (query rewrite via `QueryWithModifiableFields` in each ES module). The resolved strategy is logged under `fls`.
- Lucene-level FLS requires ROR on **all** nodes and is slow; ES-level does not. Keep this constraint in mind when reviewing FLS changes.

## Correlation ID contract

ROR ES is session-unaware; the `x-ror-correlation-id` request header (`http.scala`) correlates requests. If absent, ROR generates one (a composite string, not a bare UUID — see `RequestContext`). The metadata endpoint (`/_readonlyrest/metadata/user`) echoes the effective ID back as the `correlation_id` **JSON body field** (`MetadataResponse.scala`), NOT as a response header — there is no `x-ror-logging-id`. The ID goes into the audit log. Don't break this contract — Kibana relies on it for session-scoped log correlation.

## ROR API endpoints ↔ ES actions

Every ROR REST endpoint maps to a dedicated ES action name (used in ACL `actions` rule and audit). The **canonical** names below are `cluster:internal_ror/*` — that is what `RorAction` declares (`elasticsearch.scala`) and what audit logs emit. The older `cluster:ror/*` spellings are legacy aliases still accepted in ACL YAML for backward compat (via `rorActionByOutdatedName`) but **never** appear in audit output — don't grep audit logs for them. Keep the mapping stable:

| Endpoint | Action (canonical; `cluster:ror/*` legacy alias) |
|---|---|
| `GET /_readonlyrest/metadata/user` | `cluster:internal_ror/user_metadata/get` |
| `GET/POST /_readonlyrest/admin/config` (+ `/file`, `/refreshconfig`) | `cluster:internal_ror/config/refreshsettings` (legacy refresh), `cluster:internal_ror/config/manage` (cluster summary) |
| `POST/DELETE /_readonlyrest/admin/config/test` | `cluster:internal_ror/testconfig/manage`; in-memory test config, TTL via the `ttl` field in the POST request body (`TestSettingsApi`) |
| `POST/DELETE /_readonlyrest/admin/config/test/authmock` | `cluster:internal_ror/authmock/manage`; mocks persist until DELETE or plugin restart (no TTL in the current `AuthMockApi`) |
| `POST /_readonlyrest/admin/audit/event` | `cluster:internal_ror/audit_event/put` |

Behavioral invariants:

- **Audit event API**: body ≤ 5,000 bytes (`MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES = 5 * 1000`, not 5,120 — 413 otherwise); request is always audited, but custom fields are added only if the request is ALLOWED; custom fields must **never overwrite** default serializer fields.
- **Auth mocks** exist to support impersonation (`IMPERSONATE_AS` header) for rules backed by external services (LDAP, external authn/authz) — ROR can't query the real service with the impersonated user's credentials, so it answers from the mock. Mocks persist until an explicit `DELETE` or a plugin restart — there is no server-side TTL in the current `AuthMockApi` (unlike the test *config* endpoint, which does take a `ttl`).
- **Cluster config summary** (`cluster:internal_ror/config/manage`): the handling node propagates to all nodes and compares each response against its own config; any difference is a warning (`NODE_RETURNED_CONFIG_ERROR`, `NODE_FORCED_FILE_CONFIG`, ...); an error loading the **current** node's config fails the whole request (nothing to compare against).
- 403 body shape for all endpoints is the standard ROR forbidden JSON (`"reason":"forbidden"`); don't invent new error shapes.
