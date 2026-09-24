# ROR INTERNAL API CHANGELOG

## 4.2.0

Changes:

* header `X-ROR-KBN-License-Type`:

    The header is no longer read on GET `/_readonlyrest/metadata/user` alone. The ROR KBN plugin now
    sends it on each request to Elasticsearch, and ES ROR answers a request which carries a value it
    can parse with HTTP 403 instead of 401, and with no `WWW-Authenticate` header, while the
    `prompt_for_basic_auth` setting is on. The same value also selects the answer which ES ROR gives
    for an index that the user cannot see.

    The client sends this header, so ES ROR treats the value as a hint. Each client which sends a
    value ES ROR can parse gets the same answer. The header permits no request which ES ROR refuses
    without it. It changes the answer alone: the status, the `WWW-Authenticate` header, and, for an
    index which the user cannot see, a 404 in place of the refusal.

## 4.1.0

Changes:

* endpoint GET `/_readonlyrest/metadata/user`:

    The `kibana.index` field is now always present in the response. When a matched block does not explicitly declare a Kibana index, the default index (`.kibana`) is returned instead of the field being omitted. The field is now marked as required.

## 4.0.0

Changes:
* endpoint GET `/_readonlyrest/metadata/current_user` is now **removed** (was deprecated since version 3.1.0). Use `/_readonlyrest/metadata/user` instead.

## 3.1.0

Changes:

* endpoint GET `/_readonlyrest/metadata/current_user` is now **deprecated**. Use `/_readonlyrest/metadata/user` instead.

* new endpoint GET `/_readonlyrest/metadata/user`:

    Returns user metadata in a new format. If the user belongs to groups, it returns all groups with their metadata (type `USER_WITH_GROUPS`). If the user has no groups, it returns user metadata directly (type `USER_WITHOUT_GROUPS`). Unlike `/metadata/current_user`, this returns all data in a single call without requiring the `X-ROR-Current-Group` header.

    Supports optional `X-ROR-KBN-License-Type` header with values: `free`, `pro`, `ent`.

## 3.0.0

Changes:

* endpoint GET `/_readonlyrest/metadata/current_user`:
    
    Changes in response body:
    * `x-ror-current-group` type changed from string to an object
    * `x-ror-available-groups` type changed from an array of strings to an array of objects

* endpoint GET `/_readonlyrest/admin/config/test/authmock`:
    
    Changes in response body:
    * `groups` type changed from an array of strings to an array of objects for `LDAP` and `EXT_AUTHZ` auth services 
    
* endpoint POST `/_readonlyrest/admin/config/test/authmock`:

    Changes in request body:
    * `groups` type changed from an array of strings to an array of objects for `LDAP` and `EXT_AUTHZ` auth services

## 2.1.0

Changelog for versions <= 2.1.0 is not provided.