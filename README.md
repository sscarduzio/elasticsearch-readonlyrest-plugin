[![ghit.me](https://ghit.me/badge.svg?repo=sscarduzio/elasticsearch-readonlyrest-plugin)](https://ghit.me/repo/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Build Status](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin.svg?branch=master)](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin)

# Readonly REST Elasticsearch Plugin
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

#### Getting started

##### 1. Install the plugin 

Replace the ES version with the one you have:

```bash
bin/plugin install https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.2_es-v2.3.2.zip?raw=true
```
##### 2. Configuration

Append either of these snippets to `conf/elasticsearch.yml`

**USE CASE 1: Full access from localhost + RO Access just to catalogue-* indices**
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
      actions: [cluster:*, indices:data/read/*]
      indices: ["<no-index>", "product_catalogue-*"] # index aliases are taken in account!
```

**USE CASE 2: Multiuser Kibana + Authenticated Logstash (various permission levels)**
```yml
http.cors.enabled: true
http.cors.allow-origin: /https?:\/\/localhost(:[0-9]+)?/

readonlyrest:
    enable: true
    response_if_req_forbidden: Forbidden by ReadonlyREST ES plugin
    access_control_rules:

    - name: "Logstash can write and create its own indices"
      auth_key: logstash:logstash
      type: allow
      actions: ["indices:data/read/*","indices:data/write/*","indices:admin/template/*","indices:admin/create"]
      indices: ["logstash-*", "<no_index>"]

    - name: Kibana Server (we trust this server side component, full access granted via HTTP authentication)
      auth_key: admin:passwd3
      type: allow

    - name: Developer (reads only logstash indices, but can create new charts/dashboards)
      auth_key: dev:dev
      type: allow
      kibana_access: ro+
      indices: ["<no-index>", ".kibana*", "logstash*", "default"]

```
**Now activate authentication in Kibana server**: let the Kibana daemon connect to ElasticSearch in privileged mode.

* edit the kibana configuration file: `kibana.yml` and add the following:
````yml
elasticsearch.username: "admin"
elasticsearch.password: "passwd3"
```
The users connecting from their browsers will be asked to login separately anyways.

##### 4. restart elastic search

**For other use cases and finer access control** have a look at [the full list of supported rules](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)


### News
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

###  Download the latest build

* v1.9.3 for Elasticsearch 2.3.2 [elasticsearch-readonlyrest-v1.9.3_es-v2.3.2.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.3_es-v2.3.2.zip?raw=true)

* v1.9.1 for Elasticsearch 2.3.1 [elasticsearch-readonlyrest-v1.9.1_es-v2.3.1.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.1_es-v2.3.1.zip?raw=true)

* v1.9.1 for Elasticsearch 2.3.0 [elasticsearch-readonlyrest-v1.9.1_es-v2.3.0.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.1_es-v2.3.0.zip?raw=true)

* v1.9.1 for Elasticsearch 2.2.* is not recommended because of a [bug in ES](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/issues/35)
 
Plugin releases for **earlier versions of Elasticsearch** (may not include all the features) are available in the [download](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download) folder.

**If you need a build for a specific ES version, just open an issue!** 

## Features

#### Lightweight security :rocket:
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight HTTP request filtering layer.

#### Less moving parts
Some suggest to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. This is a bad idea for two reasons:
- You're introducing more moving parts, your architecure gains complexity.
- Reasoning about security at HTTP level is risky and less granular controlling access at the internal ES protocol level.

> The only clean way to do the access control is AFTER ElasticSearch has parsed the queries.

Just set a few rules with this plugin and confidently open for the external world.

#### A Simpler, flexible access control list (ACL)
Build your ACL from simple building blocks (rules) i.e.:

##### IP level Rules
* ```hosts``` a list of origin IP addresses or subnets

##### HTTP level rules
* ```api_keys``` a list of api keys passed in via header ```X-Api-Key```
* ```methods``` a list of HTTP methods
* ```accept_x-forwarded-for_header``` interpret the ```X-Forwarded-For``` header as origin host (useful for AWS ELB and other reverse proxies)
* ```auth_key``` HTTP Basic auth.

##### ElasticSearch level rules
* ```indices``` indices (aliases and wildcards work)
* ```actions``` list of ES [actions](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#actions-and-apis) (e.g. "cluster:*" , "indices:data/write/*", "indices:data/read*")

##### ElasticSearh level macro-rules
* ```kibana_access``` captures the read-only, read-only + new visualizations/dashboards, read-write use cases of Kibana.


See the (full list of supported rules)[Supported-Rules] for more info on how to use them.


#### Custom response body
Optionally provide a string to be returned as the body of 403 (FORBIDDEN) HTTP response. If not provided, the descriptive "name" field of the matched block will be shown (good for debug!).

## Extra
* [List of ACL block rules supported](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)
* [List of Actions and their meaning](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules#actions-and-apis)

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
