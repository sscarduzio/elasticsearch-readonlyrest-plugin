# ROR INTERNAL API CHANGELOG

## 4.2.0

Changes:

* header `X-ROR-KBN-License-Type`:

    ES ROR now reads the header on each request, not on GET `/_readonlyrest/metadata/user` alone.

    The header overrides the
    [`prompt_for_basic_auth`](https://docs.readonlyrest.com/elasticsearch#prompt_for_basic_auth)
    setting. ES ROR answers a request which carries a valid license type in the header exactly as it
    answers each request when the setting is `false`.

    GET `/_readonlyrest/metadata/user` no longer answers 403 `OPERATION_NOT_ALLOWED` when
    `prompt_for_basic_auth` is on. It follows the access control list, as when the setting is
    `false`. A request with no header, or with a license type which ES ROR cannot read, is still a
    bad request.

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