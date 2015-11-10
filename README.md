# Readonly REST Elasticsearch Plugin
This plugin makes possible to expose the high performance HTTP server embedded in Elasticsearch directly to the public  denying the access to the API calls which may change any data.

No more proxies! Yay Ponies!
###  Download the latest build

* Elastic Search 2.0.x *NEW!*  [elasticsearch-readonlyrest-v1.4_es-v2.0.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v2.0.0.zip?raw=true)

* Elastic Search 1.7.x  [elasticsearch-readonlyrest-v1.4_es-v1.7.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v1.7.1.zip?raw=true)

* Elastic Search 1.6.x  [elasticsearch-readonlyrest-v1.4_es-v1.6.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v1.6.0.zip?raw=true)

* Elastic Search 1.5.x  [elasticsearch-readonlyrest-v1.3_es-v1.5.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.5.2.zip?raw=true)

* Elastic Search 1.4.x  [elasticsearch-readonlyrest-v1.3_es-v1.4.*.zip](https://github.com/XI-lab/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.4.1.zip?raw=true)

* Elastic Search 1.3.x  [elasticsearch-readonlyrest-v1.3_es-v1.3.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.3.0.zip?raw=true)

* Elastic Search 1.2.x  [elasticsearch-readonlyrest-v1.3_es-v1.2.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.2.0.zip?raw=true)

* Elastic Search 1.1.x  [elasticsearch-readonlyrest-v1.3_es-v1.1.0.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.1.0.zip?raw=true)

* Elastic Search 1.0.1  [elasticsearch-readonlyrest-v1.3_es-v1.1.0.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.0.1.zip?raw=true)

* Elastic Search 1.0.0  [elasticsearch-readonlyrest-v1.3_es-v1.0.0.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.3_es-v1.0.0.zip?raw=true)


![](http://i.imgur.com/8CLtS1Z.jpg)

## Features

#### Lightweight security
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight HTTP request filtering layer.

#### Less moving parts
No need to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. Just set ES in "read-only" mode for the external world with one simple access control rule.

#### Flexible ACLs
Explicitly allow/forbid requests by access control rule parameters:
* ```hosts``` a list of origin IP addresses
* ```methods``` a list of HTTP methods
* ```uri_re``` a regular expression to match the request URI (useful to restrict certain indexes)
* ```maxBodyLength``` limit HTTP request body length. 

#### Custom response body
Optionally provide a string to be returned as the body of 403 (FORBIDDEN) HTTP response.

## What is this read only mode?
When the plugin is activated and properly configured, Elasticsearch REST API responds with a "403 FORBIDDEN" error whenever the request meets the following parameters:

*  Any HTTP method other than GET is requested
*  GET request has a body (according to HTTP specs it never should!)

Please see the wiki on how to [implement read only mode using a single access control rule](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/wiki/Access-Control-Rules)

This is enough to keep public users from changing the data, according to [ES REST API documentation](http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/docs.html).

You're free to expand the rules chain further if you need more fine grained access control.

## Building for a different version of Elasticsearch
Just edit pom.xml properties replacing the version number with the one needed:
```        <elasticsearch.version>0.90.7</elasticsearch.version> ```

Please note that there might be some API changes between major releases of Elasticsearch, fix the source accordingly in that case.

## Installation
### Pre-built zip file
Download the latest binary distribution of the plugin from the ```latest``` folder in this repository.
``` $ wget https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest<CHECK_LATEST_VERSION>.zip?raw=true```

Now use the Elasticsearch plugin script to install it directly:
```$ bin/plugin -url file:/tmp/elasticsearch-readonly*.zip -install readonlyrest```

### From source
Maven and elasticsearch are required.

```bash 
$ git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git
```

```bash
$ cd elasticsearch-readonlyrest-plugin
```

```bash
$ mvn package
```

```bash
$cp target/elasticsearch-readonlyrest*zip /tmp
```

Go to the Elasticsearch installation directory and install the plugin.
```bash 
$ bin/plugin -url file:/tmp/elasticsearch-readonly*.zip -install readonlyrest
```

## Configuration
This plugin can be configured directly from within 
```bash 
$ES_HOME/conf/elasticsearch.yml
```
E.g.

```yaml
readonlyrest:
    # (De)activate plugin
    enable: true

    # HTTP response body in case of forbidden request.
    # If this is null or omitted, the name of the first violated access control rule is returned (useful for debugging!)
    response_if_req_forbidden: Sorry, your request is forbidden

    # Default policy is to forbid everything, let's define a whitelist
    access_control_rules:
    
    # from these IP addresses, accept any method, any URI, any HTTP body
    - name: full access to internal servers
      type: allow
      hosts: [127.0.0.1, 10.0.0.20, 10.0.2.112]

    # From any other hosts, check first they are not accessing private indexes
    - name: forbid access to private index from external hosts
      type: forbid
      uri_re: ^http://localhost:9200/reservedIdx/.*

    # From external hosts, accept only GET and OPTION methods only if the HTTP requqest body is empty
    - name: restricted access to all other hosts
      type: allow
      methods: [OPTIONS,GET]
      maxBodyLength: 0

```


### Some testing 

Let's check regular gets are allowed:

```bash
$ curl -v -XGET http://localhost:9200/dummyindex/_search
* About to connect() to localhost port 9200 (#0)
*   Trying ::1...
* connected
* Connected to localhost (::1) port 9200 (#0)
> GET /dummyindex/_search HTTP/1.1
> User-Agent: curl/7.24.0 (x86_64-apple-darwin12.0) libcurl/7.24.0 OpenSSL/0.9.8y zlib/1.2.5
> Host: localhost:9200
> Accept: */*
>
< HTTP/1.1 200 OK
< Content-Type: application/json; charset=UTF-8
< Content-Length: 223
<
* Connection #0 to host localhost left intact
{"took":45,"timed_out":false,"_shards":{"total":1,"successful":1,"failed":0},"hits":{"total":1,"max_score":1.0,"hits":[{"_index":"dummyindex","_type":"dummyType","_id":"dummy","_score":1.0, "_source" : {"dummy":"dummy"}}]}}* Closing connection #0
```

A GET request with a body gets barred

```bash
$ curl -v -XGET http://localhost:9200/dummyindex/_search -d 'some body text'
* About to connect() to localhost port 9200 (#0)
*   Trying ::1...
* connected
* Connected to localhost (::1) port 9200 (#0)
> GET /dummyindex/_search HTTP/1.1
> User-Agent: curl/7.24.0 (x86_64-apple-darwin12.0) libcurl/7.24.0 OpenSSL/0.9.8y zlib/1.2.5
> Host: localhost:9200
> Accept: */*
> Content-Length: 14
> Content-Type: application/x-www-form-urlencoded
>
* upload completely sent off: 14 out of 14 bytes
< HTTP/1.1 403 Forbidden
< Content-Type: text/plain; charset=UTF-8
< Content-Length: 14
<
* Connection #0 to host localhost left intact
Sorry, your request is forbidden 
* Closing connection #0
```

A GET request whose URI includes the string "bar_me_pls"
```bash
$ curl -v -XGET http://localhost:9200/dummyindex/bar_me_pls/_search
* About to connect() to localhost port 9200 (#0)
*   Trying ::1...
* connected
* Connected to localhost (::1) port 9200 (#0)
> GET /dummyindex/bar_me_pls/_search HTTP/1.1
> User-Agent: curl/7.24.0 (x86_64-apple-darwin12.0) libcurl/7.24.0 OpenSSL/0.9.8y zlib/1.2.5
> Host: localhost:9200
> Accept: */*
>
< HTTP/1.1 403 Forbidden
< Content-Type: text/plain; charset=UTF-8
< Content-Length: 14
<
* Connection #0 to host localhost left intact
Sorry, your request is forbidden
* Closing connection #0
```

A POST request gets barred (as any other non-GET)

```bash
$ curl -v -XPOST http://localhost:9200/dummyindex/_search
* About to connect() to localhost port 9200 (#0)
*   Trying ::1...
* connected
* Connected to localhost (::1) port 9200 (#0)
> POST /dummyindex/_search HTTP/1.1
> User-Agent: curl/7.24.0 (x86_64-apple-darwin12.0) libcurl/7.24.0 OpenSSL/0.9.8y zlib/1.2.5
> Host: localhost:9200
> Accept: */*
>
< HTTP/1.1 403 Forbidden
< Content-Type: text/plain; charset=UTF-8
< Content-Length: 14
<
* Connection #0 to host localhost left intact
Sorry, your request is forbidden
* Closing connection #0
```

## Uninstallation instructions
```bash
 $ $ES_HOME/bin/plugin -url ./target -remove readonlyrest
```

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
