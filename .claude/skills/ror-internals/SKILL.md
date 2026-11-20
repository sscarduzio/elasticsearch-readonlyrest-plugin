---
name: ror-internals
description: ReadonlyREST internal architecture knowledge — kibana rule semantics and access table (BaseKibanaRule.isAllowed with KibanaAccessTable, shared by the kibana and legacy kibana_access rules), FLS engine strategies, correlation-ID header contract, ROR admin API endpoints and their ES action names. Use when coding or reviewing changes to the kibana rules, FLS/fields rule, audit logging, or the _readonlyrest admin/metadata APIs.
---

# ROR Internals (ES plugin)

Distilled from `beshu-tech/readonlyrest-internal/architecture/` and `api/`. Treat as design intent; the code is authority (update this on discrepancy).

## kibana rule

The `kibana` rule (and the legacy `kibana_access` rule, deprecated since 1.48.0) decides with an access table: one row per access level, one column per target of the request. The table and its legend are in the comment of `KibanaAccessTable`. The code is the authority.

Design intent:
- `ro_strict`, `ro` and `api_only` only read. `ro` and `api_only` also do the Kibana UI writes to the main Kibana index ("Discover" state, short URLs, data views).
- `rw` writes the Kibana indices and reads the cluster configuration. `admin` is `rw` plus the ROR settings API. It is not a cluster administrator.
- No restricted level writes data indices or the cluster configuration. The only exception: `rw` and `admin` can add and remove the Kibana sample data sets.
- An action that a new ES version adds is allowed only for `unrestricted`, until someone adds it to a group.
- A change of a cell or of an action group is a behavioral change. Review it as one, and update the docs table ("Possible access levels" in `readonlyrest-docs/elasticsearch.md`).

Facts that the code does not show:
- In ES, `api_only` has the row of `ro`. The ROR Kibana plugin denies its browser login and allows only `/api/...` paths (`allowed_api_paths`).
- ES runs every request that ROR allows as its internal `_xpack` user. So all ROR users share one API key owner, and ROR cannot limit a user to "own API keys".
- The kibana rule never sees the ROR audit event request: `RegularRule.check` permits it for every regular rule.
- An explicit `kibana.access: unrestricted` exists so that a YAML anchor can override a templated `rw`.

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
