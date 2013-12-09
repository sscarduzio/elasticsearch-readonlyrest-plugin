# Readonly Rest ES Plugin

This plugin makes possible to expose the high performance HTTP server embedded in Elasticsearch directly to the public  denying the access to the API calls which may change any data.

No more proxies! Yay Ponies!

## Features

#### Lightweight security
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight HTTP request filtering layer.

#### Less moving parts
No need to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. Just set ES in "read-only" mode for the external world.

#### Flexible ACLs
Optionally provide a white-list of server that need unrestricted access for.
Optionally provide a regular expression to match unwanted URI patterns.

#### Custom response body
Optionally provide a string to be returned as the body of 403 (FORBIDDEN) HTTP response.

## What is this read only mode?
When the plugin is activated, Elasticsearch REST API responds with a "403 FORBIDDEN" error whenever the request meets the following parameters:

*  Any HTTP method other than GET is requested
*  GET request has a body (according to HTTP specs it never should!)

This is enough to keep public users from changing the data, according to [ES REST API documentation](http://www.elasticsearch.org/guide/en/elasticsearch/reference/current/docs.html).

If you feel you need to augment the restrictions, a regular expression URI matcher is provided.

## Building this project for a different version of Elasticsearch
Just edit pom.xml properties replacing the version number with the one needed:
```        <elasticsearch.version>0.90.7</elasticsearch.version> ```

Please note that there might be some API changes between major releases of Elasticsearch, fix the source accordingly in that case.

## Installation
Maven and elasticsearch are required.

```$ git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git```

```$ cd elasticsearch-readonlyrest-plugin```

```$ mvn package```

```$ $ES_HOME/bin/plugin -url ./target -install readonlyrest```


## Configuration
This plugin can be configured directly from within ``` $ES_HOME/conf/elasticsearch.yml```

Here is what a typical configuration may look like:
```
readonlyrest:
        enable: true
        allow_localhost: false
        whitelist: [10.0.0.20, 10.0.2.112]
        forbidden_uri_re: .*bar_me_pls.*
        barred_reason_string: <h1>Rejected</h1>

```

That means:
* the plugin is enabled
* localhost accesses the API in read only mode.
* IP addresses 10.0.0.20 and 10.0.2.112 have unrestricted access
* All URIs matching the regular expression ´´´.*bar_me_pls.*´´´ will be immediately rejected. 
* When rejecting, use ```<h1>Rejected</h1>``` 
as the body of the HTTP response 

### Some testing 

Let's check regular gets are allowed:

```
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

```
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
<h1>Rejected</h1>
* Closing connection #0
```

A GET request whose URI includes the string "bar_me_pls"
```
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
<h1>Rejected</h1>
* Closing connection #0
```

A POST request gets barred (as any other non-GET)

```
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
<h1>Rejected</h1>
* Closing connection #0
```

## Uninstallation instructions
```$ $ES_HOME/bin/plugin -url ./target -remove readonlyrest```

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
