[![ghit.me](https://ghit.me/badge.svg?repo=sscarduzio/elasticsearch-readonlyrest-plugin)](https://ghit.me/repo/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Build Status](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin.svg?branch=master)](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin)

# Readonly REST Elasticsearch Plugin
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

## Getting started

### 1. Install the plugin 

List of other supported Elasticsearch versions: [releases](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/releases) tab.
**If you need a build for a specific ES version, just open an issue!** 

```bash
ES_VERSION=2.4.1
bin/plugin install https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/releases/download/v1.10.0_es-v$ES_VERSION/elasticsearch-readonlyrest-v1.10.0_es-v$ES_VERSION.zip
```

### 2. Configuration

Append either of these snippets to `conf/elasticsearch.yml`

### USE CASE 0: Enable SSL globally
Remember to enable SSL whenever you use HTTP basic auth or API keys so your credentials can't be stolen.
```yml
readonlyrest:
    enable: true
    
    ssl:
      enable: true
      keystore_file: "/elasticsearch/plugins/readonlyrest/keystore.jks"
      keystore_pass: readonlyrest
      key_pass: readonlyrest
```

### USE CASE 1: Full access from localhost + RO to catalogue-* indices from elsewhere

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
      indices: ["<no-index>", "product_catalogue-*"] # index aliases are taken in account!
```

> The `<no-index>` is for matching those generic requests that don't actually  involve an index (e.g. get cluster state). More about this in the [wiki](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#a-note-on-no-index).

### USE CASE 2: Multiuser Kibana + Authenticated Logstash (various permission levels)
```yml
# remember to set the right CORS origin (or disable it, if you're brave). See https://github.com/elastic/kibana/issues/6719
http.cors.enabled: true
http.cors.allow-origin: /https?:\/\/localhost(:[0-9]+)?/

readonlyrest:
    enable: true
    
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    
    access_control_rules:

    - name: "Logstash can write and create its own indices"
      # auth_key is good for testing, but replace it with `auth_key_sha1`!
      auth_key: logstash:logstash
      type: allow
      actions: ["indices:data/read/*","indices:data/write/*","indices:admin/template/*","indices:admin/create"]
      indices: ["logstash-*", "<no-index>"]

    - name: Kibana Server (we trust this server side component, full access granted via HTTP authentication)
      # auth_key is good for testing, but replace it with `auth_key_sha1`!
      auth_key: admin:passwd3
      type: allow

    - name: Developer (reads only logstash indices, but can create new charts/dashboards)
      # auth_key is good for testing, but replace it with `auth_key_sha1`!
      auth_key: dev:dev
      type: allow
      kibana_access: ro+
      indices: ["<no-index>", ".kibana*", "logstash*", "default"]

```
**Now activate authentication in Kibana server**: let the Kibana daemon connect to ElasticSearch in privileged mode.

* edit the kibana configuration file: `kibana.yml` and add the following:

```yml
elasticsearch.username: "admin"
elasticsearch.password: "passwd3"
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
      uri_re: ^/index1/.*

    - name: Accept requests from users in group team2 on index2
      type: allow
      groups: ["team2"]
      uri_re: ^/index2/.*

    - name: Accept requests from users in groups team1 or team2 on index3
      type: allow
      groups: ["team1", "team2"]
      uri_re: ^/index3/.*
    
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

### 3. Restart Elasticsearch

**For other use cases and finer access control** have a look at [the full list of supported rules](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)

### Important!
Before going to production, read this.

#### disallow explicit indices 
When you want to restrict access to certain indices, in order to prevent the user from overriding the index which has been specified in the URL, add this setting to the config.yml file:

```yml
rest.action.multi.allow_explicit_index: false
```

The default value is true, but when set to false, Elasticsearch will reject requests that have an explicit index specified in the request body.

#### Use hashed credentials
Plain text `auth_key` is is great for testing, but remember to replace it with [`auth_key_sha1`](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#list-of-supported-rules-in-acl-blocks)! 


## Key Features

#### Zero external dependencies: tiny memory overhead, blazing fast networking :rocket:
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight pure-Java filtering layer. Even the SSL layer is provided as an extra Netty transport handler.

#### Less moving parts
Some suggest to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. This is a **bad idea** for two reasons:
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
