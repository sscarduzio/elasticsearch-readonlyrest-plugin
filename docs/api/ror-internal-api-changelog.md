# ROR INTERNAL API CHANGELOG

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