[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Build Status](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin.svg?branch=master)](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Twitter URL](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/readonlyrest)
[![Patreon](http://i.imgur.com/Fw6Kft4.png)](https://www.patreon.com/readonlyrest)

## ReadonlyREST needs your help ⚠️
ReadonlyREST is an GPLv3 open source project. Its ongoing development can only made possible thanks to the support of its backers:

1. @nmaisonneuve 
2. @Id57 
3. PPRO
4. Jeff Saxe
3. Joseph Bull

If you care this project **keeps on existing**, read up the [ReadonlyREST Patreon campaign](https://www.patreon.com/readonlyrest).

# Readonly REST Elasticsearch Plugin
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

## Quick start

This is a quck guide. For more detailed documentation see our [Elasticsearch plugin documentation](https://github.com/beshu-tech/readonlyrest-docs/blob/master/elasticsearch.md) 

### 1. Install the plugin 

[Download](https://readonlyrest.com/download.html) the binary release for your Elasticsearch version from the official website.


### 2. Configuration

Append either of these snippets to `readonlyrest.yml`. This file should reside right on the same path with `elasticsearch.yml`.

### USE CASE: Secure public searchbox from [ransomware](http://code972.com/blog/2017/01/107-dont-be-ransacked-securing-your-elasticsearch-cluster-properly)
```yml
readonlyrest:
    access_control_rules: 

    - name: "::PUBLIC SEARCHBOX::"
      indices: ["public"]
      actions: ["indices:data/read/*"]
```

### USE CASE: Enable HTTPS globally
Remember to enable SSL whenever you use HTTP basic auth or API keys so your credentials can't be stolen.

add this to `config/elasticsearch.yml`:
```yml
http.type: ssl_netty4 
```

And then add this to `conf/readonlyrest.yml`
```yml
readonlyrest:
    enable: true # optional, defaults=true if at least 1 "access_control_rules" block
    
    ssl:
      enable: true
      keystore_file: "/elasticsearch/plugins/readonlyrest/keystore.jks"
      keystore_pass: readonlyrest
      key_pass: readonlyrest
```

### USE CASE: Full access for localhost, RO some indices from elsewhere

```yml
readonlyrest:
    #optional
    response_if_req_forbidden: Sorry, your request is forbidden.
    
    access_control_rules:

    - name: Accept all requests from localhost
      hosts: [127.0.0.1]

    - name: Just certain indices, and read only
      actions: ["indices:data/read/*"]
      indices: ["product_catalogue-*"] # index aliases are taken in account!
```

###  USE CASE: Unix Authentication

This method is based on /etc/shadow file syntax. 

For example if you configured sha512 encryption with 65535 rounds on your system the hash in /etc/shadow for the account
`test:test` will be `test:$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0` 

```yml
readonlyrest:
    access_control_rules:
    - name: Accept requests from users in group team1 on index1
      groups: ["team1"]
      indices: ["index1"]
    
    users:
    - username: test
      auth_key_unix: test:$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0 #test:test
      groups: ["team1"]

```

You can generate the hash with **mkpasswd** command, you need whois package `apt-get install whois`

`mkpasswd -m sha-512 -R 65534`

Also you can generate the hash with a python or perl script:
```python
#!/usr/bin/python
import crypt
import random
import sys
import string



def sha512_crypt(password, salt=None, rounds=None):
    if salt is None:
        rand = random.SystemRandom()
        salt = ''.join([rand.choice(string.ascii_letters + string.digits)
                        for _ in range(8)])

    prefix = '$6$'
    if rounds is not None:
        rounds = max(1000, min(999999999, rounds or 5000))
        prefix += 'rounds={0}$'.format(rounds)
    return crypt.crypt(password, prefix + salt)


if __name__ == '__main__':
    if len(sys.argv) > 1:
        print sha512_crypt(sys.argv[1], rounds=65635)
    else:
        print "Argument is missing, <password>"
```
**Finally you have to put your username at the begining of the hash with ":" separator**
`test:$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0`

For example, `test` is the username and `$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0` is the hash


###  USE CASE: Multi-user Kibana + Authenticated Logstash
```yml

readonlyrest:

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
      actions: ["cluster:monitor/main","indices:admin/types/exists","indices:data/read/*","indices:data/write/*","indices:admin/template/*","indices:admin/create"]
      indices: ["logstash-*"]

    # We trust Kibana's server side process, full access granted via HTTP authentication
    - name: "::KIBANA-SRV::"
      # auth_key is good for testing, but replace it with `auth_key_sha256`!
      auth_key: kibana:kibana
      verbosity: error # don't log successful request

    # Using "Basic HTTP Auth" from browsers, can RW Kibana settings, RO on logstash indices from 2017 .
    - name: "::RW DEVELOPER::"
      auth_key: rw:dev
      kibana_access: rw
      indices: [".kibana", ".kibana-devnull", "logstash-2017*"]

    # Same as above, but cannot change dashboards, visualizations or settings in Kibana
    - name: "::RO DEVELOPER::"
      auth_key: ro:dev
      kibana_access: ro
      indices: [".kibana", ".kibana-devnull", "logstash-2017*"]

```
**Now activate authentication in Kibana server**: let the Kibana daemon connect to Elasticsearch in privileged mode.

* edit the kibana configuration file: `kibana.yml` and add the following:

```yml
elasticsearch.username: "kibana"
elasticsearch.password: "kibana"
```

This is secure because the users connecting from their browsers will be asked to login separately anyways.

**Now activate authenticatoin in Logstash**: [(follow the docs, it's very similar to Kibana!)](https://www.elastic.co/guide/en/shield/current/logstash.html#ls-http-auth-basic)

### USE CASE: Group-based access control
```yml
readonlyrest:
    access_control_rules:

    - name: Accept requests from users in group team1 on index1
      groups: ["team1"]
      indices: ["index1"]

    - name: Accept requests from users in group team2 on index2
      groups: ["team2"]
      indices: ["index2"]

    - name: Accept requests from users in groups team1 or team2 on index3
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

### USE CASE: Authentication via LDAP + Authorization via groups

#### Simpler: authentication and authorization in one rule
```yml
readonlyrest:
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:

    - name: Accept requests from users in group team1 on index1
      ldap_auth:
          name: "ldap1"                                       # ldap name from below 'ldaps' section
          groups: ["g1", "g2"]                                # group within 'ou=Groups,dc=example,dc=com'
      indices: ["index1"]
      
    - name: Accept requests from users in group team2 on index2
      ldap_auth:
          - name: "ldap2"
            groups: ["g3"]
            cache_ttl_in_sec: 60
      indices: ["index2"]

    ldaps:
    
    - name: ldap1
      host: "ldap1.example.com"
      port: 389                                                 # optional, default 389
      ssl_enabled: false                                        # optional, default true
      ssl_trust_all_certs: true                                 # optional, default false
      bind_dn: "cn=admin,dc=example,dc=com"                     # optional, skip for anonymous bind
      bind_password: "password"                                 # optional, skip for anonymous bind
      search_user_base_DN: "ou=People,dc=example,dc=com"
      user_id_attribute: "uid"                                  # optional, default "uid"
      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      unique_member_attribute: "uniqueMember"                   # optional, default "uniqueMember"
      connection_pool_size: 10                                  # optional, default 30
      connection_timeout_in_sec: 10                             # optional, default 1
      request_timeout_in_sec: 10                                # optional, default 1
      cache_ttl_in_sec: 60                                      # optional, default 0 - cache disabled
    
    - name: ldap2
      host: "ldap2.example2.com"
      port: 636
      search_user_base_DN: "ou=People,dc=example2,dc=com"
      search_groups_base_DN: "ou=Groups,dc=example2,dc=com"
```

#### Advanced: authentication and authorization in separate rules
```yml
readonlyrest:
    access_control_rules:

    - name: Accept requests from users in group team1 on index1
      ldap_authentication: "ldap1"  
      ldap_authorization:
        name: "ldap1"                                       # ldap name from 'ldaps' section
        groups: ["g1", "g2"]                                # group within 'ou=Groups,dc=example,dc=com'
      indices: ["index1"]
      
    - name: Accept requests from users in group team2 on index2
      ldap_authentication:
        name: "ldap2"  
        cache_ttl_in_sec: 60
      ldap_authorization:
        name: "ldap2"
        groups: ["g3"]
        cache_ttl_in_sec: 60
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
      user_id_attribute: "uid"                                  # default "uid"
      search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
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

(An example OpenLDAP configuration file can be found in our tests: /src/test/resources/test_example.ldif)

Caching can be configured per LDAP client (see `ldap1`) or per rule (see `Accept requests from users in group team2 on index2` rule)

### USE CASE: External Basic HTTP Authentication
ReadonlyREST will forward the received `Authorization` header to a website of choice and evaluate the returned HTTP status code to verify the provided credentials.
This is useful if you already have a web server with all the credentials configured and the credentials are passed over the `Authorization` header.

```yml
readonlyrest:
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:
    
    - name: "::Tweets::"
      methods: GET
      indices: ["twitter"]
      external_authentication: "ext1"

    - name: "::Facebook posts::"
      methods: GET
      indices: ["facebook"]
      external_authentication:
        service: "ext2"
        cache_ttl_in_sec: 60

    external_authentication_service_configs:

    - name: "ext1"
      authentication_endpoint: "http://external-website1:8080/auth1"
      success_status_code: 200
      cache_ttl_in_sec: 60

    - name: "ext2"
      authentication_endpoint: "http://external-website2:8080/auth2"
      success_status_code: 204
      cache_ttl_in_sec: 60
```

To define an external authentication service the user should specify: 
- `name` for service (then this name is used as id in `service` attribute of `external_authentication` rule)
- `authentication_endpoint` (GET request)
- `success_status_code` - authentication response success status code

Cache can be defined at the service level or/and at the rule level. In the example, both are shown, but you might opt for setting up either.

### USE CASE: External groups provider: XML/JSON service (external authorization) 
This external authorization connector makes it possible to resolve to what groups a users belong, using an external JSON or XML service.

```yml
readonlyrest:
    access_control_rules:

    - name: "::Tweets::"
      methods: GET
      indices: ["twitter"]
      proxy_auth:
        proxy_auth_config: "proxy1"
        users: ["*"]
      groups_provider_authorization:
        user_groups_provider: "GroupsService"
        groups: ["group3"]

    - name: "::Facebook posts::"
      methods: GET
      indices: ["facebook"]
      proxy_auth:
        proxy_auth_config: "proxy1"
        users: ["*"]
      groups_provider_authorization:
        user_groups_provider: "GroupsService"
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
      cache_ttl_in_sec: 60
      default_headers:                                         # default headers to be passed with api call
        header1: value1
        header2: value2
      default_query_parameters:                                # default params to be passed with api calls
        param1: value1
        param2: value2
      http_method: get/post                                    # method to invoke GET/POST.  
```

In example above, a user is authenticated by reverse proxy and then external service is asked for groups for that user. 
If groups returned by the service contain any group declared in `groups` list, user is authorized and rule matches. 

To define user groups provider you should specify:
- `name` for service (then this name is used as id in `user_groups_provider` attribute of `groups_provider_authorization` rule)
- `groups_endpoint` - service with groups endpoint (GET request)
- `auth_token_name` - user identifier will be passed with this name
- `auth_token_passed_as` - user identifier can be send using HEADER or QUERY_PARAM
- `response_groups_json_path` - response can be unrestricted, but you have to specify JSON Path for groups name list (see example in tests)

As usual, the cache behaviour can be defined at service level or/and at rule level.

### 3. Restart Elasticsearch

**For other use cases and finer access control** have a look at the official documentation to see [the full list of supported rules](https://readonlyrest.com/documentation)


#### Use hashed credentials
Plain text `auth_key` is is great for testing, but remember to replace it with [`auth_key_sha256`](https://readonlyrest.com/documentation)! 


## Key Features

#### Tiny memory overhead, blazing fast networking :rocket:
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight pure-Java filtering layer. Even the SSL layer is provided as an extra Netty transport handler.

#### Fewer moving parts
Some suggest to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to filter out malicious access with regular expressions on HTTP methods and paths. This is a **bad idea** for two reasons:
- You're introducing more complexity in your architecture.
- Reasoning about security at HTTP level is risky, flaky and less granular than controlling access at the internal Elasticsearch protocol level.

**The only clean way to do the access control is AFTER Elasticsearch has parsed the queries.**

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

##### Elasticsearch internal protocol level rules
* ```indices``` indices (aliases and wildcards work)
* ```actions``` list of ES [actions](https://readonlyrest.com/documentation/#Rules--Rules--Elasticsearch_level--Action_rule) (e.g. "cluster:*" , "indices:data/write/*", "indices:data/read*")

##### ElasticSearh level macro-rules
* ```kibana_access``` captures the read-only, read-only + new visualizations/dashboards, read-write use cases of Kibana.

## All the available rules in detail
* [Official website detailed documentation](https://github.com/beshu-tech/readonlyrest-docs/blob/master/elasticsearch.md)

## Contributor License Agreement

By contributing your code to ReadonlyREST you grant its owner Simone Scarduzio a non-exclusive, irrevocable, worldwide, royalty-free, sublicenseable, transferable license under all of Your relevant intellectual property rights (including copyright, patent, and any other rights), to use, copy, prepare derivative works of, distribute and publicly perform and display the Contributions on any licensing terms, including without limitation: (a) open source licenses like the GPLv3 license; and (b) binary, proprietary, or commercial licenses. Except for the licenses granted herein, You reserve all right, title, and interest in and to the Contribution.

You confirm that you are able to grant us these rights. You represent that You are legally entitled to grant the above license. If Your employer has rights to intellectual property that You create, You represent that You have received permission to make the Contributions on behalf of that employer, or that Your employer has waived such rights for the Contributions.

You represent that the Contributions are Your original works of authorship, and to Your knowledge, no other person claims, or has the right to claim, any right in any invention or patent related to the Contributions. You also represent that You are not legally obligated, whether by entering into an agreement or otherwise, in any way that conflicts with the terms of this license.

The owner of the ReadonlyREST project Simone Scarduzio acknowledges that, except as explicitly described in this Agreement, any Contribution which you provide is on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, OR FITNESS FOR A PARTICULAR PURPOSE.

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
