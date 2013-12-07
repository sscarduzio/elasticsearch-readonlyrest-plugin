# Readonly Rest ES Plugin

This plugin makes possible to expose the high performance HTTP server embedded in Elasticsearch directly to the public.
Please refer to [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow") for further explanation about what problem this plugin solves.

## Behavioural changes introduced 
This Elasticsearch plugin responds with a HTTP 403 FORBIDDEN error whenever the clients request meets the following parameters:

*  GET request has a body
*  Any HTTP method other than GET is requested
*  GET request contains "bar_me_pls" in the raw path. (for ease of test, obviously)

## Building for a different version of Elasticsearch
Just edit pom.xml properties replacing the version number with the one needed:
```        <elasticsearch.version>0.90.7</elasticsearch.version> ```

Please note that there might be some API changes between major releases of Elasticsearch, fix the source accordingly in that case.

## Installation instructions
Maven and elasticsearch are required.

```$ git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git```

```$ cd elasticsearch-readonlyrest-plugin```

```$ mvn package```

```$ $ES_HOME/bin/plugin -url ./target -install readonlyrest```

## Test it
Regular gets are allowed:

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
barred request* Closing connection #0
```

A GET request with the test string "bar_me_pls"
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
barred request* Closing connection #0
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
barred request* Closing connection #0
```

## Uninstallation instructions
```$ $ES_HOME/bin/plugin -url ./target -remove readonlyrest```

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
