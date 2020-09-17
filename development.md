# Development setup for readonly rest plugin
These instructions are valid to run the whole ElasticSearch code base as a Java application in your IDE. Additionally, we'll see how to hook up the plugin source code, so we'll be able to debug line by line an instance of ElasticSearch with a plugin installed (as source code).
## Clone readonly rest plugin
This project contains the ElasticSearch dependencies in the Maven build system, so all the ElasticSearch classes will be in the class path and we'll be able to run the whole thing without a problem.
`git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git /me/elasticsearch-readonlyrest-plugin`
For this example, we cloned the source code under a short path: the `/me` directory.
## Run ES as a Java Application
Open IntelliJ Idea and open the source folder as a maven project. The dependencies will be downloaded automatically.
Now click on the drop down menu on the left of the green play icon on the top right and select "Edit Configurations.."
![Dropdown edit configurations](http://i.imgur.com/cmqNA7T.png)
Click the plus icon on the left and create a new "Application". Now fill in all the fields as shown in the pic:
![IntelliJ Idea Run Configuration](https://i.imgur.com/csPfgkw.png)
That is:
- **VM Options** `-Djava.security.policy=/me/elasticsearch-readonlyrest-plugin/integration-tests/src/test/eshome/plugins/readonlyrest/plugin-security.policy -Djava.awt.headless=true -Des.insecure.allow.root=true -Dfile.encoding=UTF-8 -Djna.nosys=true -Des.path.home=/me/elasticsearch-readonlyrest-plugin/integration-tests/src/test/eshome -Dlog4j2.disable.jmx=true -Xms1g -Xmx1g -Djdk.io.permissionsUseCanonicalPath=true -Dio.netty.noUnsafe=true -Dio.netty.noKeySetOptimization=true -Dio.netty.recycler.maxCapacityPerThread=0 -Dlog4j.shutdownHookEnabled=false -Dlog4j.skipJansi=true -Des.path.conf=/me/elasticsearch-readonlyrest-plugin/integration-tests/src/test/eshome/config/ -Djava.security.manager -Djava.security.policy=/me/elasticsearch-readonlyrest-plugin/integration-tests/src/test/eshome/sec-all.policy`
- **Main class** `org.elasticsearch.bootstrap.Elasticsearch`
- **Use classpath of module** `readonlyrest.es[YY]x.main`
The careful reader may already have noticed that some Java options points to a special folder under `integration-tests/src/test/eshome`. This contains what ElasticSearch normally expects to see in the present working directory it's being run.
```
$ tree eshome
.
├── bin
│   └── no_delete
├── config
│   ├── elasticsearch.yml
│   ├── log4j2.properties
│   ├── readonlyrest.yml
│   ├── scripts
│   └── [...]
├── data
├── lib
│   └── no_delete
├── logs
├── modules
│   ├── transport-netty4
│      ├── plugin-descriptor.properties
│      └── plugin-security.policy
└── plugins
    └── readonlyrest
        ├── plugin-descriptor.properties
        └── plugin-security.policy
```
Notice the plugins/readonlyrest contains a copy of the descriptor properties and security policy, but not the plugin's jar. This is because - as stated in the beginning - the plugin code is already available to the class path in form of source files.
## Run it
Now save this, call it with a name like `Whole ES` and you'll be able to press play and see ElasticSearch boot up in your IDE.
![ES booting up and running in IDE](http://i.imgur.com/A4DfsWZ.png)
## Building plugin using Gradle for concrete ES version:
* `./gradlew clean es70x:ror '-PesVersion=7.2.0'` 
* ROR plugin binaries can found in `es70x/build/distributions/`
## Running tests
* unit tests: `./gradlew test ror`
* integration tests for specific module (at the moment we have two modules with integration tests): 
  * `./gradlew integration-tests:test '-PesModule=es70x'` 
  * `./gradlew integration-tests-scala:test '-PesModule=es70x'`
## Adding license headers to newly created files:
* `./gradlew licenseFormatMain`
* `./gradlew licenseFormatTest`
## Testing with LDAP
If you need to setup local LDAP you can use LDAPServer defined in our tests sources: `tech.beshu.ror.utils.ldap.LDAPServer`
## Troubleshooting
### #1 
If you try to run `Whole ES` and you get `jar hell` related errors, refer to this issue: https://github.com/elastic/elasticsearch/issues/14348 The explanation will guide you through on how to remove `ant-javafx.jar` from the SDK classpath.
Solving this SDK issue will also address the issue of when the IDE won't find some imports (but maven still can and builds correctly). At least for me it did.
### #2
If you experienced `java.lang.NoClassDefFoundError: org/elasticsearch/plugins/ExtendedPluginsClassLoader`, you should add proper plugin-classloader jar to your class path. In IntellijIdea you should do following steps:
* figure out which ES module you are trying to run
* check concrete ES version defined in `es[XY]x` module in `gradle.properties` file
* go to File -> Project Structure -> Libraries
* click `+` -> java
* pick `integration-tests/src/test/eshome/lib/plugin-classloader-[ABC].jar` (the ABC version should be the same as defined in `gradle.properties` mentioned above)
* select `readonlyrest.es[XY]x.main`
* apply
### #3
If you experienced `org.elasticsearch.common.xcontent.XContentParseException: [-1:36] [node_meta_data] unknown field [node_version], parser not found` or similar, you shouold remove `integration-tests/src/test/eshome/data` folder and try again.
### #4
If you see:
* `org.elasticsearch.bootstrap.StartupException: java.lang.IllegalArgumentException: Plugin [transport-netty4] was built for Elasticsearch version A.B.C but version X.Y.Z is running`
* `org.elasticsearch.bootstrap.StartupException: java.lang.IllegalArgumentException: Plugin [readonlyrest] was built for Elasticsearch version A.B.C but version X.Y.Z is running`
you should change following properties:
* `version` and `elasticsearch.version` of `integration-tests/src/test/eshome/modules/transport-netty4/plugin-descriptor.properties` from A.B.C to X.Y.Z
* `elasticsearch.version` of `integration-tests/src/test/eshome/plugins/readonlyrest/plugin-descriptor.properties` from A.B.C to X.Y.Z