== About Readonly Rest Elasticsearch Plugin ==
This plugin makes possible to expose the high performance HTTP server embedded in Elasticsearch directly to the public.
Please refer to this StackOverflow thread for further rationale: http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head

== Behavioural changes introduced == 
This Elasticsearch plugin responds with a HTTP 403 FORBIDDEN error whenever the clients request meets the following parameters:

* GET request has a body 
* Any HTTP method other than GET is requested
* GET request contains "bar_me_pls" in the raw path. (for ease of test, obviously)

== Building for a different version of Elasticsearch ==
Just edit pom.xml properties replacing the version number with the one needed: 
```        <elasticsearch.version>0.90.7</elasticsearch.version> ```
Please note that there might be some API changes between major releases of Elasticsearch, fix the source accordingly in that case. 

== Installation instructions == 
Maven and elasticsearch are required.

```$ git clone git@github.com:brusic/elasticsearch-hello-world-plugin.git```

```$ cd elasticsearch-readonlyrest-plugin```

```$ mvn package```

```$ $ES_HOME/bin/plugin -url ./target -install readonlyrest```

== Uninstallation instructions === 
```$ $ES_HOME/bin/plugin -url ./target -remove readonlyrest```
