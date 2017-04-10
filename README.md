[![ghit.me](https://ghit.me/badge.svg?repo=sscarduzio/elasticsearch-readonlyrest-plugin)](https://ghit.me/repo/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Build Status](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin.svg?branch=master)](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Patreon](http://i.imgur.com/Fw6Kft4.png)](https://www.patreon.com/readonlyrest)

## ReadonlyREST needs your help ⚠️
ReadonlyREST is an GPLv3 open source project. Its ongoing development can only made possible thanks to the support of its backers:

1. @nmaisonneuve 
2. @Id57 
3. Joseph Bull

If you care this project **keeps on existing**, read up the [ReadonlyREST Patreon campaign](https://www.patreon.com/readonlyrest).

# Readonly REST Elasticsearch Plugin
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

## Getting started

### 1. Install the plugin 

[Download](https://readonlyrest.com) the binary release for your Elasticsearch version from the official website.


### 2. Configuration

Append either of these snippets to `conf/elasticsearch.yml`

### USE CASE 0: Enable HTTPS
globally
Remember to enable SSL whenever you use HTTP basic auth or API keys so your credentials can't be stolen.
```yml
http.type: ssl_netty4
readonlyrest:
    enable: true
    
    ssl:
      enable: true
      keystore_file: "/elasticsearch/plugins/readonlyrest/keystore.jks"
      keystore_pass: readonlyrest
      key_pass: readonlyrest
```

### USE CASE 1: RW all indices from localhost, RO specific indices from elsewhere

```yml
readonlyrest:
    enable: true
    response_if_req_forbidden: Sorry, your request is forbidden.
    
    access_control_rules:

    - name: Accept all requests from localhost
      type: allow
      hosts: [127.0.0.1]

    - name: Just certain indices, and read only
      type: allow
      actions: ["indices:data/read/*"]
      indices: ["product_catalogue-*"] # index aliases are taken in account!
```

### USE CASE 2: Multi-tenant Kibana + Authenticated Logstash (various permission levels)
```yml

readonlyrest:
    enable: true
    ssl:
      enable: true
      keystore_file: "/elasticsearch/plugins/readonlyrest/keystore.jks"
      keystore_pass: readonlyrest
      key_pass: readonlyrest

    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin

    access_control_rules:

    - name: "::LOGSTASH::"
      # auth_key is good for testing, but replace it with `auth_key_sha1`!
      auth_key: logstash:logstash
      type: allow
      actions: ["indices:admin/types/exists","indices:data/read/*","indices:data/write/*","indices:admin/template/*","indices:admin/create"]
      indices: ["logstash-*"]

    # We trust Kibana's server side process, full access granted via HTTP authentication
    - name: "::KIBANA-SRV::"
      # auth_key is good for testing, but replace it with `auth_key_sha256`!
      auth_key: kibana:kibana
      type: allow

    # Using "Basic HTTP Auth" from browsers, can RW Kibana settings, RO on logstash indices from 2017 .
    - name: "::RW DEVELOPER::"
      auth_key: rw:dev
      type: allow
      kibana_access: rw
      indices: [".kibana", ".kibana-devnull", "logstash-2017*"]

    # Same as above, but cannot change dashboards, visualizations or settings in Kibana
    - name: "::RO DEVELOPER::"
      auth_key: ro:dev
      type: allow
      kibana_access: ro
      indices: [".kibana", ".kibana-devnull", "logstash-2017*"]

    # No authentication required to read from "public" index
    - name: "::PUBLIC SEARCH::"
      type: allow
      indices: ["public"]
      actions: ["indices:data/read/*"]


```
**Now activate authentication in Kibana server**: let the Kibana daemon connect to ElasticSearch in privileged mode.

* edit the kibana configuration file: `kibana.yml` and add the following:

```yml
elasticsearch.username: "kibana"
elasticsearch.password: "kibana"
```

This is secure because the users connecting from their browsers will be asked to login separately anyways.

**Now activate authenticatoin in Logstash**: [(follow the docs, it's very similar to Kibana!)](https://www.elastic.co/guide/en/shield/current/logstash.html#ls-http-auth-basic)

### USE CASE 3: Group-based access control
```yml
readonlyrest:
    enable: true
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:

    - name: Accept requests from users in group team1 on index1
      type: allow
      groups: ["team1"]
      indices: ["index1"]

    - name: Accept requests from users in group team2 on index2
      type: allow
      groups: ["team2"]
      indices: ["index2"]

    - name: Accept requests from users in groups team1 or team2 on index3
      type: allow
      groups: ["team1", "team2"]
      indices: ["index3"]
    
    users:
    
    - username: alice
      auth_key: alice:p455phrase
      groups: ["team1"]
      
    - username: bob
      auth_key: bob:s3cr37
      groups: ["team2", "team4"]
      
    - username: claire
      auth_key_sha1: 2bc37a406bd743e2b7a4cb33efc0c52bc2cb03f0 #claire:p455key
      groups: ["team1", "team5"]

```

### USE CASE 4: LDAP authentication and group-based authorization
```yml
readonlyrest:
    enable: true
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:

    - name: Accept requests from users in group team1 on index1
      type: allow
      ldap_auth:
          - name: "ldap1"                                       # ldap name from 'ldaps' section
            groups: ["g1", "g2"]                                # group within 'ou=Groups,dc=example,dc=com'
          - name: "ldap2"
            groups: ["g3", "g4"]
      indices: ["index1"]
      
    - name: Accept requests from users in group team2 on index2
      type: allow
      ldap_auth:
          - name: "ldap2"
            groups: ["g3"]
      indices: ["index2"]

    ldaps:
    
    - name: ldap1
      host: "ldap1.example.com"
      port: 389                                                 # default 389
      ssl_enabled: false                                        # default true
      ssl_trust_all_certs: true                                 # default false
      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
      bind_password: "password"                                 # skip for anonymous bind
      search_user_base_DN: "ou=People,dc=example,dc=com"
      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      connection_pool_size: 10                                  # default 30
      connection_timeout_in_sec: 10                             # default 1
      request_timeout_in_sec: 10                                # default 1
      cache_ttl_in_sec: 60                                      # default 0 - cache disabled
    
    - name: ldap2
      host: "ldap2.example2.com"
      port: 636
      search_user_base_DN: "ou=People,dc=example2,dc=com"
      search_groups_base_DN: "ou=Groups,dc=example2,dc=com"
```

LDAP configuration requirements:
- user from `search_user_base_DN` should have `uid` attribute (can be overwritten using `user_id_attribute`)
- groups from `search_groups_base_DN` should have `uniqueMember` attribute (can be overwritten using `unique_member_attribute`)

(example LDAP config can be found in test /src/test/resources/test_example.ldif)

### USE CASE 5: External groups provider for group-based authorization

```yml
readonlyrest:
    enable: true
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:

    - name: "::Tweets::"
      type: allow
      methods: GET
      indices: ["twitter"]
      proxy_auth:
        - proxy_auth_config: "proxy1"
          users: ["*"]
      groups_provider_authorization:
        - user_groups_provider: "GroupsService"
          groups: ["group3"]

    - name: "::Facebook posts::"
      type: allow
      methods: GET
      indices: ["facebook"]
      proxy_auth:
        - proxy_auth_config: "proxy1"
          users: ["*"]
      groups_provider_authorization:
        - user_groups_provider: "GroupsService"
          groups: ["group1"]
          cache_ttl_in_sec: 60

    proxy_auth_configs:

    - name: "proxy1"
      user_id_header: "X-Auth-Token"                           # default X-Forwarded-User

    user_groups_providers:

    - name: GroupsService
      groups_endpoint: "http://localhost:8080/groups"
      auth_token_name: "token"
      auth_token_passed_as: QUERY_PARAM                        # HEADER OR QUERY_PARAM
      response_groups_json_path: "$..groups[?(@.name)].name"   # see: https://github.com/json-path/JsonPath
```

In example above, a user is authenticated by reverse proxy and then external service is asked for groups for that user. 
If groups returned by the service contain any group declared in `groups` list, user is authorized and rule matches. 

To define user groups provider you should specify:
- `name` for service (then this name is used as id in `user_groups_provider` attribute of `groups_provider_authorization` rule)
- `groups_endpoint` - service with groups endpoint (GET request)
- `auth_token_name` - user identifier will be passed with this name
- `auth_token_passed_as` - user identifier can be send using HEADER or QUERY_PARAM
- `response_groups_json_path` - response can be unrestricted, but you have to specify JSON Path for groups name list (see example in tests)

### 3. Restart Elasticsearch

**For other use cases and finer access control** have a look at the official documentation to see [the full list of supported rules](https://readonlyrest.com/documentation)

### Important!
Before going to production, read this.

#### disallow explicit indices 
When you want to restrict access to certain indices, in order to prevent the user from overriding the index which has been specified in the URL, add this setting to the config.yml file:

```yml
rest.action.multi.allow_explicit_index: false
```

The default value is true, but when set to false, Elasticsearch will reject requests that have an explicit index specified in the request body.

#### Use hashed credentials
Plain text `auth_key` is is great for testing, but remember to replace it with [`auth_key_sha256`](https://readonlyrest.com/documentation)! 


## Key Features

#### Tiny memory overhead, blazing fast networking :rocket:
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight pure-Java filtering layer. Even the SSL layer is provided as an extra Netty transport handler.

#### Less moving parts
Some suggest to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to filter out malicious access with regular expressions on HTTP methods and paths. This is a **bad idea** for two reasons:
- You're introducing more complexity in your architecture.
- Reasoning about security at HTTP level is risky, flaky and less granular than controlling access at the internal ElasticSearch protocol level.

**The only clean way to do the access control is AFTER ElasticSearch has parsed the queries.**

Just set a few rules with this plugin and confidently open it up to the external world.

#### An easy, flexible access control list
Build your ACL from simple building blocks (rules) i.e.:

##### IP level Rules
* ```hosts``` a list of origin IP addresses or subnets

##### HTTP level rules
* ```api_keys``` a list of api keys passed in via header ```X-Api-Key```
* ```methods``` a list of HTTP methods
* ```accept_x-forwarded-for_header``` interpret the ```X-Forwarded-For``` header as origin host (useful for AWS ELB and other reverse proxies)
* ```auth_key_sha1``` HTTP Basic auth (credentials stored as hashed strings).
* ```uri_re``` Match the URI path as a regex.

##### ElasticSearch internal protocol level rules
* ```indices``` indices (aliases and wildcards work)
* ```actions``` list of ES [actions](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#actions-and-apis) (e.g. "cluster:*" , "indices:data/write/*", "indices:data/read*")

##### ElasticSearh level macro-rules
* ```kibana_access``` captures the read-only, read-only + new visualizations/dashboards, read-write use cases of Kibana.

## All the available rules in detail
* [List of ACL block rules supported](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)
* [List of Actions and their meaning](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#actions-and-apis)

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
