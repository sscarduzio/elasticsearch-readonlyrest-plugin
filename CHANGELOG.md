
 
### Changelog


## Unreleased

## Released
> 2016-10-17 :new: v1.11.0:
* **feature** Support for groups of users in ACL, thanks Christian Henke for the great PR!
* **bugfix**  Make ```auth_key_sha1``` behave like ````auth_key``` rule: return 401 instead of 403 status code if Authorization header was required

> 2016-09-24 :new: v1.10.0: * SSL support: prevent basic HTTP Auth credential sniffing. + Hashed credentials: ```auth_key_sha1``` store hashed basic HTTP auth credentials in configuration file. 

> 2016-09-06 :new: v1.9.5:  **security fix** in `indices` and `kibana_access` rules **PLEASE UPGRADE IMMEDIATELY**   

> 2016-07-11 :new: v1.9.4:  bugfix release (NPE in debug logs when no indices were found. Resolves #76)

> 2016-04-26 :new: v1.9.3:  Tighter Kibana access rule + Indices rule supports <no-index> (for cluster commands, etc) useful for restricting Kibana rules to certain indices only (see example 2)

> 2016-04-26 :new: v1.9.2:  bugfix release

> 2016-02-21 :new: v1.9.1:  
* ```kibana_access``` support access control for Kibana dashboards in  "ro|rw|ro+" modes.
* ```kibana_indices``` if you customize the `kibana.index` property in `kibana.yml` let us know so `kibana_access` works as it should.
* ```actions``` rule lets you control what kind of actions are allowed/forbidden. I.e. `[cluster:*, indices:data:*]` 
* ```indices``` rule now supports wildcards i.e. the word `logstash-*` will match itself, but also `logstash-2016-04-02` 

> 2016-02-21 :new: v1.8:  ```indices``` rule now resolves index aliases.

> 2016-02-21 :new: v1.7: **real** (multi)index isolation is now possible through ```indices``` rule (supersedes ```uri_re```).

> 2016-02-20 :new: v1.6: show login prompt in browsers if ```auth_key``` is configured.

> 2015-12-19  :new: v1.5: support for ```X-Forwarded-For```, HTTP Basic Authentication, and ```X-API-Key```.

