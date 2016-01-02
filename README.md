[![ghit.me](https://ghit.me/badge.svg?repo=sscarduzio/elasticsearch-readonlyrest-plugin)](https://ghit.me/repo/sscarduzio/elasticsearch-readonlyrest-plugin)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)

# Readonly REST Elasticsearch Plugin
This plugin makes possible to expose the high performance HTTP server embedded in Elasticsearch directly to the public  denying the access to the API calls which may change any data.

No more proxies! Yay Ponies!

### News
> 2015-12-19 New features in v1.5: support for ```X-Forwarded-For```, HTTP Plain Auth, and ```X-API-Key```.

###  Download the latest build

* Elastic Search 2.1.1 **New Features!** [elasticsearch-readonlyrest-v1.5_es-v2.1.1.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.5_es-v2.1.1.zip?raw=true)

* Elastic Search 2.1.x **New Features!** [elasticsearch-readonlyrest-v1.5_es-v2.1.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.5_es-v2.1.0.zip?raw=true)

* Elastic Search 2.1.x [elasticsearch-readonlyrest-v1.4_es-v2.1.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v2.1.0.zip?raw=true)

* Elastic Search 2.0.x [elasticsearch-readonlyrest-v1.4_es-v2.0.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v2.0.0.zip?raw=true)

* Elastic Search 1.7.x  [elasticsearch-readonlyrest-v1.4_es-v1.7.*.zip](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest-v1.4_es-v1.7.1.zip?raw=true)

Plugin releases for **earlier versions of Elasticsearch** are available in the [download](https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download) folder.

![](http://i.imgur.com/8CLtS1Z.jpg)

## Features

#### Lightweight security
Other security plugins are replacing the high performance, Netty based, embedded REST API of Elasticsearch with Tomcat, Jetty or other cumbersome XML based JEE madness.

This plugin instead is just a lightweight HTTP request filtering layer.

#### Less moving parts
No need to spin up a new HTTP proxy (Varnish, NGNix, HAProxy) between ES and clients to prevent malicious access. Just set ES in "read-only" mode for the external world with one simple access control rule.

#### Flexible ACLs
Explicitly allow/forbid requests by access control rule parameters:
* ```hosts``` a list of origin IP addresses or subnets
* ```api_keys``` a list of api keys passed in via header ```X-Api-Key```
* ```methods``` a list of HTTP methods
* ```accept_x-forwarded-for_header``` interpret the ```X-Forwarded-For``` header as origin host (useful for AWS ELB and other reverse proxies)
* ```uri_re``` a regular expression to match the request URI (useful to restrict certain indexes)
* ```maxBodyLength``` limit HTTP request body length.
* ```auth_key``` HTTP Basic auth. The value is a clear text (*non BASE64-encoded*) key. Only HTTP requests with the right ```Auth: BASE64-encoded-secred-key``` header will match.

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
```        <elasticsearch.version>2.1.1</elasticsearch.version> ```

Please note that there might be some API changes between major releases of Elasticsearch, fix the source accordingly in that case.

## Installation
### Pre-built zip file: direct install
In modern version of Elasticsearch (e.g. 2.1.1), you should be able to install readonlyrest plugin directly like this:

```bash
$ cd $ES_HOME
$ sudo bin/plugin install https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/raw/master/download/elasticsearch-readonlyrest-v1.5_es-v2.1.1.zip
```
If this fails for any reason, make sure the URL points to the package of the right Elasticsearch version!
If the version is correct and this still fails, you can just download the zip and install it from the file system as shown below.

### Pre-built zip file: download and install
- Download the latest binary distribution of the plugin from the ```latest``` folder in this repository.

``` $ wget https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/blob/master/download/elasticsearch-readonlyrest<CHECK_LATEST_VERSION>.zip?raw=true```

- Use the Elasticsearch plugin script to install it directly:

```$ bin/plugin -url file:/tmp/elasticsearch-readonly*.zip -install readonlyrest```

### Building From Source
Maven and elasticsearch are required.

```bash 
$ git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git
```

```bash
$ cd elasticsearch-readonlyrest-plugin
```

```bash
$ ./build.sh
```

```bash
$cp target/elasticsearch-readonlyrest*zip /tmp
```

Go to the Elasticsearch installation directory and install the plugin.
```bash 
$ bin/plugin -url file:/tmp/elasticsearch-readonly*.zip -install readonlyrest
```

## Configuration
This plugin can be configured directly from within the elastic search main configuration file:

```bash 
$ES_HOME/conf/elasticsearch.yml
```
E.g.

```yaml
readonlyrest:
    # (De)activate plugin
    enable: true

    # The HTTP response body to return in case of forbidden request.
    # If this is null or omitted, the name of the first violated access control rule is returned (useful for debugging!)
    response_if_req_forbidden: Sorry, your request is forbidden.
	
    # Default policy is to forbid everything, let's define a whitelist
    access_control_rules:
    
    # Basic Authorisation key (clients should send this key base64-encoded in the 'Auth' header)  
    - name: full access if Basic HTTP auth
      type: allow
      auth_key: MyPasswordPlainText

    # From these IP addresses, accept any method, any URI, any HTTP body
    - name: full access to internal servers
      type: allow
      hosts: [127.0.0.1, 10.0.1.0/24]

    # Allow if the origin host or the host in X-Forwarded-For header is 9.9.9.9 (useful for AWS ELB and other reverse proxies)
    - name: full access to internal servers
      type: allow
      accept_x-forwarded-for_header: true
      hosts: [9.9.9.9]

    # From these API Keys, accept any method, any URI, any HTTP body
    - name: full access to remote authorized clients
      type: allow
      api_keys: [abcdefghijklmnopqrstuvwxyz, a1b2c3d4e5f6]

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

## Uninstall instructions

```bash
 $ $ES_HOME/bin/plugin -url ./target -remove readonlyrest
```

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")
