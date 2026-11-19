---
name: ror-internals
description: ReadonlyREST internal architecture knowledge — kibana rule semantics and access table (BaseKibanaRule.isAllowed with KibanaAccessTable, shared by the kibana and legacy kibana_access rules), FLS engine strategies, correlation-ID header contract, ROR admin API endpoints and their ES action names. Use when coding or reviewing changes to the kibana rules, FLS/fields rule, audit logging, or the _readonlyrest admin/metadata APIs.
---

# ROR Internals (ES plugin)

Distilled from `beshu-tech/readonlyrest-internal/architecture/` and `api/`. Treat as design intent; the code is authority (update this on discrepancy).

## kibana rule

Shared decision: `BaseKibanaRule.isAllowed` (`core/.../blocks/rules/kibana/BaseKibanaRule.scala`). It classifies the request once (`KibanaRequest`) and looks it up in the access table (`KibanaAccessTable`). Two concrete rules extend it:
- **Current**: `KibanaUserDataRule.scala` — YAML name `kibana`.
- **Legacy**: `KibanaAccessRule.scala` — YAML name `kibana_access`, `@deprecated` since `1.48.0`.

Action groups are Scala `PatternsMatcher` vals in `KibanaActionMatchers.scala` — there is no `Constants.java`. Each group names the ES actions **in full, with no wildcard**, generated from the ES action lists (`readonlyrest-docs/actionstrings`, ES 6.7.0 to 9.5.6). An action that a new ES version adds is allowed only for `unrestricted` until someone adds it to a group:
- `readActions` — reads of indices, data streams and aliases, and the cluster reads that Kibana needs for the read only levels (health, nodes, license, ML views, Dev Tools, ES|QL autocompletion…).
- `writeActions` — document writes, index and data stream creation, mapping updates. Index or data stream delete, alias updates and the other index management actions are cluster management, not here.
- `clusterManagementReadActions` — reads of the cluster configuration and state, without the secret values (Fleet and connector secrets) and without the cluster reads that return index data (datafeed, DFA and transform previews, `find_field_structure`): ROR finds no indices in those requests, so the `indices` rule cannot check them.
- `nonStrictActions` — with a non-strict path (see ¹).
- `rorAdminActions` — the ROR settings API, from `RorAction.adminActions`.
- Cluster management writes (cluster settings, templates, ILM/SLM, pipelines, snapshots, index management, API key changes, X-Pack features…) are in no group: only `unrestricted` allows them.

- Omitting `kibana_access` ≡ `unrestricted`. The explicit `unrestricted` value exists so YAML anchor/template blocks can **override** a templated `rw` default.
- Design intent of all restricted levels: the Kibana indices are writable for `rw` and `admin` (saved objects must work), all other "data indices" are **read-only**, and no restricted level writes the cluster configuration.
- `admin` is `rw` **plus** the ROR settings API (`rorAdminActions`). It is not a cluster administrator.

The access table has the shape of the docs table ("Possible access levels" in `readonlyrest-docs/elasticsearch.md`, published `master`, plus the internal access table): one row per level, one column per target (`KibanaRequest.Target`):
- `KibanaIndices`: all indices of the request are related to the Kibana index (`isRelatedToKibanaIndex`);
- `DataIndices`: the request has other indices;
- `NoIndices`: `bc.indices` is empty. A data stream request with no backing indices is here too.

All levels also allow the ROR user metadata request (by its ROR action). The kibana rule never sees the ROR audit event request: `RegularRule.check` permits it for every regular rule, by path. Each row checks each column:

| level | Kibana indices | data indices | Kibana sample data | no indices |
|---|---|---|---|---|
| `ro_strict` | read | read | read | read |
| `ro`, `api_only` | read; non-strict writes¹ | read | read | read |
| `rw` | read; write; cluster read | read; cluster read | all actions | read; cluster read |
| `admin` | as `rw` | as `rw` | all actions | as `rw`; `rorAdminActions` (the ROR settings API, whose requests have no indices) |
| `unrestricted` | all actions | all actions | all actions | all actions |

"read" = `readActions`, "write" = `writeActions`, "cluster read" = `clusterManagementReadActions`. "Kibana sample data" = the request names exactly one index or exactly one data stream, and it matches `kibana_sample_data_*`. This target wins over the others.

¹ A narrow allow-list of saved-object writes of the Kibana UI: the non-strict path (`KibanaRequest.isNonStrictPath`: discover UI state, short URLs, index-pattern/url/config doc updates, telemetry) AND `nonStrictActions` (document writes only). It is not full kibana-index write access. `rw` and `admin` do not need it: `writeActions` has all document writes.

`api_only` (YAML value `api_only`) is its own `KibanaAccess` value, not an alias. On the ES side it has the row of `ro`, **not** `ro_strict`. Its distinct restrictions are on the Kibana side: the ROR Kibana plugin denies the browser login and allows only Kibana `/api/...` paths, filtered by `allowed_api_paths` (`KibanaUserDataRuleDecoder.scala`).

API keys: ES runs every request that ROR allows as its internal `_xpack` user (`addXpackUserAuthenticationHeader`), so all ROR users share one API key owner; "own API keys" cannot be enforced per ROR user.

The table is an OR of independent cells, so the order of the checks does not change the result. **A change of a cell or of an action group is a behavioral change** — review it accordingly.

## FLS (fields rule) engines

Code: `FieldsFiltering.scala` — per ES-version module, e.g. `es94x/src/main/scala/tech/beshu/ror/es/handler/response/FieldsFiltering.scala` (NOT `core/`; uses `XContentMapValues.filter`, same method as ES source filtering). The strategy types (`FlsAtLuceneLevelApproach`, `BasedOnBlockContextOnly`, `RequestFieldsUsage`) live in `core/.../domain/elasticsearch.scala`; strategy resolution is in the fields rule.

- Engines: `es_with_lucene` (default — ES handles, lucene fallback), `es` (no fallback), and a **hidden, undocumented `lucene` engine**: full old-style lucene handling, kept as an emergency escape hatch when the ES-level path has a bug. Never remove it; never document it publicly.
- Request field usage trifurcation (`RequestFieldsUsage`): `NotUsingFields` (best case), `UsingFields` (extractable → not-allowed fields get obfuscated, e.g. `field1` → `field1_ROR_12Xb593a24`), `CannotExtractFields` (query_string, function_score, wildcards, scripts — no way to enumerate fields → must fall back to lucene).
- Resolved strategies: `FlsAtLuceneLevelApproach` (thread-context header set per ES module), `BasedOnBlockContextOnly.EverythingAllowed`, `BasedOnBlockContextOnly.NotAllowedFieldsUsed` (query rewrite via `QueryWithModifiableFields` in each ES module). The resolved strategy is logged under `fls`.
- Lucene-level FLS requires ROR on **all** nodes and is slow; ES-level does not. Keep this constraint in mind when reviewing FLS changes.

## Correlation ID contract

ROR ES is session-unaware; the `x-ror-correlation-id` request header (`http.scala`) correlates requests. If absent, ROR generates one as a **bare UUID** (`CorrelationId.random`, `http.scala`). The metadata endpoint (`/_readonlyrest/metadata/user`) echoes that `CorrelationId` back as the `correlation_id` **JSON body field** (`MetadataResponse.scala`), NOT as a response header — there is no `x-ror-logging-id`. Don't confuse it with `RequestContext.Id` (`RequestContext.scala`), the separate internal per-request id of the form `<correlationId>-<hashCode>#<taskId>` used in log output — that composite is NOT what the metadata endpoint returns. The ID goes into the audit log; Kibana relies on this contract for session-scoped log correlation.

## ROR API endpoints ↔ ES actions

Every ROR REST endpoint maps to a dedicated ES action name (used in ACL `actions` rule and audit). The **canonical** names below are `cluster:internal_ror/*` — that is what `RorAction` declares (`elasticsearch.scala`) and what audit logs emit. The older `cluster:ror/*` spellings are legacy aliases still accepted in ACL YAML for backward compat (via `rorActionByOutdatedName`) but **never** appear in audit output — don't grep audit logs for them. Keep the mapping stable:

| Endpoint | Action (canonical; `cluster:ror/*` legacy alias) |
|---|---|
| `GET /_readonlyrest/metadata/user` | `cluster:internal_ror/user_metadata/get` |
| `POST /_readonlyrest/admin/refreshconfig` | `cluster:internal_ror/config/refreshsettings` |
| `GET/POST /_readonlyrest/admin/config` (+ `/config/file`, `/config/audit`) | `cluster:internal_ror/config/manage` (all served by `MainSettingsApi`) |
| `POST/DELETE /_readonlyrest/admin/config/test` | `cluster:internal_ror/testconfig/manage`; in-memory test config, TTL via the `ttl` field in the POST request body (`TestSettingsApi`) |
| `POST/DELETE /_readonlyrest/admin/config/test/authmock` | `cluster:internal_ror/authmock/manage`; mocks persist until DELETE or plugin restart (no TTL in the current `AuthMockApi`) |
| `POST /_readonlyrest/admin/audit/event` | `cluster:internal_ror/audit_event/put` |

Behavioral invariants:

- **Audit event API**: body ≤ 5,000 bytes (`MAX_AUDIT_EVENT_REQUEST_CONTENT_IN_BYTES = 5 * 1000`, not 5,120 — 413 otherwise); request is always audited, but custom fields are added only if the request is ALLOWED; custom fields must **never overwrite** default serializer fields.
- **Auth mocks** exist to support impersonation (`IMPERSONATE_AS` header) for rules backed by external services (LDAP, external authn/authz) — ROR can't query the real service with the impersonated user's credentials, so it answers from the mock. Mocks persist until an explicit `DELETE` or a plugin restart — there is no server-side TTL in the current `AuthMockApi` (unlike the test *config* endpoint, which does take a `ttl`).
- **Cluster config summary** (`cluster:internal_ror/config/manage`): the handling node propagates to all nodes and compares each response against its own config; any difference is a warning (`NODE_RETURNED_CONFIG_ERROR`, `NODE_FORCED_FILE_CONFIG`, ...); an error loading the **current** node's config fails the whole request (nothing to compare against).
- 403 body shape for all endpoints is the standard ROR forbidden JSON (`"reason":"forbidden"`); don't invent new error shapes.
