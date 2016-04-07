[![ghit.me](https://ghit.me/badge.svg?repo=sscarduzio/elasticsearch-readonlyrest-plugin)](https://ghit.me/repo/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Build Status](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin.svg?branch=master)](https://travis-ci.org/sscarduzio/elasticsearch-readonlyrest-plugin)

# Readonly REST Elasticsearch Plugin

[![Join the chat at https://gitter.im/sscarduzio/elasticsearch-readonlyrest-plugin](https://badges.gitter.im/sscarduzio/elasticsearch-readonlyrest-plugin.svg)](https://gitter.im/sscarduzio/elasticsearch-readonlyrest-plugin?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

#### Getting started

##### 1. Install the plugin 

Replace the ES version with the one you have:

```bash
bin/plugin install https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/raw/master/download/elasticsearch-readonlyrest-v1.7_es-v2.2.1.zip
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
      indices: [product_catalogue-*] # index aliases are taken in account!

```

**USE CASE 2: Authenticated users in Kibana (various permission levels)**

```yml
readonlyrest:
    enable: true
    response_if_req_forbidden: <h1>Forbidden</h1>    
    access_control_rules:

    - name: Salesmen (read only)
      type: allow
      kibana_access: ro
      auth_key: sales:passwd1

    - name: Managers (read only, but can create dashboards)
      type: allow
      kibana_access: ro+
      auth_key: manager:passwd2
    
    - name: Admin (read write)
      type: allow
      kibana_access: rw
      auth_key: admin:passwd3
```

##### 3. restart elastic search

**For other use cases and finer access control** have a look at [the full list of supported rules](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)


### News
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

* v1.9.1 for Elasticsearch 2.3.1 [elasticsearch-readonlyrest-v1.9.1_es-v2.3.1.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.1_es-v2.3.1.zip?raw=true)

* v1.9.1 for Elasticsearch 2.3.0 [elasticsearch-readonlyrest-v1.9.1_es-v2.3.0.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.9.1_es-v2.3.0.zip?raw=true)

* v1.9.1 for Elasticsearch 2.2.* is not recommended because of a [bug in ES](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/issues/35)
 
Plugin releases for **earlier versions of Elasticsearch** (may not include all the features) are available in the [download](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download) folder.

** If you need a build for a specific ES version, open an issue and you'll get it. ** 

## Features

#### Lightweight security :rocket:
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight HTTP request filtering layer.

#### Less moving parts
No need to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. Just set ES in "read-only" mode for the external world with one simple access control rule.

#### A Simpler, flexible access control list (ACL)
Build your ACL from simple building blocks (rules) i.e.:
* ```hosts``` a list of origin IP addresses or subnets
* ```api_keys``` a list of api keys passed in via header ```X-Api-Key```
* ```methods``` a list of HTTP methods
* ```accept_x-forwarded-for_header``` interpret the ```X-Forwarded-For``` header as origin host (useful for AWS ELB and other reverse proxies)
* ```auth_key``` HTTP Basic auth. 

See the (full list of supported rules)[Supported-Rules] for more info on how to use them.


#### Custom response body
Optionally provide a string to be returned as the body of 403 (FORBIDDEN) HTTP response. If not provided, the descriptive "name" field of the matched block will be shown (good for debug!).

## What is this read only mode?
When the plugin is activated and properly configured, Elasticsearch REST API responds with a "403 FORBIDDEN" error whenever the request meets the following parameters:

*  Any HTTP method other than GET is requested
*  GET request has a body (according to HTTP specs it never should!)

This is enough to keep public users from changing the data (see relevant [ES REST API documentation](http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/docs.html)).

You're free to expand the ACL further if you need more fine grained access control.

## Extra
* [Install, Uninstall, Build this plugin](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Install,-Uninstall,-Build)
* [List of ACL block rules supported](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Supported-Rules)

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
