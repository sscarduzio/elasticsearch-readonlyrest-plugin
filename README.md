
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/tech.beshu.ror/audit_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/tech.beshu.ror/audit_2.12)
[![Codacy Badge](https://api.codacy.com/project/badge/grade/9ef51ae1e6e34deba913f22e2e4cbd56)](https://www.codacy.com/app/scarduzio/elasticsearch-readonlyrest-plugin)
[![Twitter URL](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/readonlyrest)


| Develop branch | Master branch |
|---|---|
| [![Build Status Develop](https://dev.azure.com/beshu-tech/ReadonlyREST%20for%20Elasticsearch/_apis/build/status/ror?branchName=develop)](https://dev.azure.com/scarduzio/ROR/_build/latest?definitionId=1&branchName=develop) | [![Build Status Master](https://dev.azure.com/beshu-tech/ReadonlyREST%20for%20Elasticsearch/_apis/build/status/ror?branchName=master)](https://dev.azure.com/scarduzio/ROR/_build/latest?definitionId=1&branchName=master) |
## Supporters 
Thanks **Jeff Saxe** for donating!

[![Patreon](http://i.imgur.com/Fw6Kft4.png)](https://www.patreon.com/readonlyrest) [![Liberapay](https://liberapay.com/assets/widgets/donate.svg)](https://liberapay.com/sscarduzio/donate) 

---

# Readonly REST Elasticsearch Plugin
Expose the high performance HTTP server embedded in Elasticsearch directly to the public, safely blocking any attempt to delete or modify your data.

In other words... no more proxies! Yay Ponies!
![](http://i.imgur.com/8CLtS1Z.jpg)

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

## All the available rules in detail
* [Official website detailed documentation](https://docs.readonlyrest.com/elasticsearch#rules)

## Contributor License Agreement

By contributing your code to ReadonlyREST you grant its owner Simone Scarduzio a non-exclusive, irrevocable, worldwide, royalty-free, sublicenseable, transferable license under all of Your relevant intellectual property rights (including copyright, patent, and any other rights), to use, copy, prepare derivative works of, distribute and publicly perform and display the Contributions on any licensing terms, including without limitation: (a) open source licenses like the GPLv3 license; and (b) binary, proprietary, or commercial licenses. Except for the licenses granted herein, You reserve all right, title, and interest in and to the Contribution.

You confirm that you are able to grant us these rights. You represent that You are legally entitled to grant the above license. If Your employer has rights to intellectual property that You create, You represent that You have received permission to make the Contributions on behalf of that employer, or that Your employer has waived such rights for the Contributions.

You represent that the Contributions are Your original works of authorship, and to Your knowledge, no other person claims, or has the right to claim, any right in any invention or patent related to the Contributions. You also represent that You are not legally obligated, whether by entering into an agreement or otherwise, in any way that conflicts with the terms of this license.

The owner of the ReadonlyREST project Simone Scarduzio acknowledges that, except as explicitly described in this Agreement, any Contribution which you provide is on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING, WITHOUT LIMITATION, ANY WARRANTIES OR CONDITIONS OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, OR FITNESS FOR A PARTICULAR PURPOSE.

## History
This project was incepted in [this StackOverflow thread](http://stackoverflow.com/questions/20406707/using-cloudfront-to-expose-elasticsearch-rest-api-in-read-only-get-head "StackOverflow").

## Credits
Thanks Ivan Brusic for publishing [this guide](http://blog.brusic.com/2011/09/create-pluggable-rest-endpoints-in.html "Ivan Brusic blog")

## Development guide
Development guide is available [here](development.md)
