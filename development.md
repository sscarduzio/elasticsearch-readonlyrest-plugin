# Development setup for readonly rest plugin
These instructions are valid to run the whole ElasticSearch code base as a Java application in your IDE. Additionally, we'll see how to hook up the plugin source code, so we'll be able to debug line by line an instance of ElasticSearch with a plugin installed (as source code).
## Clone readonly rest plugin
This project contains the ElasticSearch dependencies in the Maven build system, so all the ElasticSearch classes will be in the class path and we'll be able to run the whole thing without a problem.
`git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git /me/elasticsearch-readonlyrest-plugin`
For this example, we cloned the source code under a short path: the `/me` directory.
## Set proper JDK version
Current version of ROR requires JDK 17. 
## Run ES as a Java Application
Gradle project named `eshome` is configured to run ROR as java application. You can run it from console
```bash
./gradlew clean eshome:runEs
```
or using Intellij for debug. ![Dropdown edit configurations](https://i.imgur.com/KiFSyD9.png)

ES version and ROR ES module can be defined in gradle.properties. Currently, eshome should support ES 8.x

```
$ tree eshome
.
├── bin
├── config
│   ├── elasticsearch.yml
│   ├── log4j2.properties
│   ├── readonlyrest.yml
│   ├── scripts
│   └── [...]
├── data
├── lib
├── logs
├── modules
└── plugins
```
Notice the plugins/readonlyrest contains a copy of the descriptor properties and security policy, but not the plugin's jar. This is because - as stated in the beginning - the plugin code is already available to the class path in form of source files.
## Run it
Now save this, call it with a name like `Whole ES` and you'll be able to press play and see ElasticSearch boot up in your IDE.
![ES booting up and running in IDE](https://i.imgur.com/A4DfsWZ.png)
## Debug with concrete ES version
Currently eshome support debugging only es8x modules.
![ES booting up and running in IDE](https://i.imgur.com/s32SaI8.png) 
## Building plugin using Gradle for concrete ES version:

### Using Gradle
* `./gradlew clean [ROR_ES_MODULE]:ror '-PesVersion=[ES_VERSION]'` 
* eg. `./gradlew clean es70x:ror '-PesVersion=7.2.0'`
* ROR plugin binaries can be found in `es70x/build/distributions/`

**⚠️Required tools:**
* OpenJDK 17
* Gradle 4.10

### Using Docker
* `cd docker-envs/build-ror-in-docker && /build.sh [ES_VERSION]`
* eg. `cd docker-envs/build-ror-in-docker && /build.sh 8.2.2`
* ROR plugin bunaries can be found in `docker-envs/build-ror-in-docker/builds`

**⚠️Required tools:**
* Docker

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
### #5
```
Caused by: java.lang.IllegalStateException: codebase property already set: codebase.readonlyrest -> file:/home/wdk/multirepo/elasticsearch-readonlyrest-plugin/eshome/plugins/readonlyrest/readonlyrest-1.25.0_es7.9.3.jar, cannot set to file:/home/wdk/multirepo/elasticsearch-readonlyrest-plugin/eshome/plugins/readonlyrest/readonlyrest-1.25.0_es7.10.0.jar
```
ES above version 7.9.x uses plugin class loaders based on plugin dirs, so dependency jars are copied like are copied for installed plugin, to plugin's dir. You should clean `eshome` project.
`./gradlew :eshome:clean`
### #6 
If you see in Idea:
```
Unable to make protected void java.net.URLClassLoader.addURL(java.net.URL) accessible: module java.base does not "opens java.net" to unnamed module @17d99928
```
you should go to `File->ProjectStructure->SDK` and pick JDK different than 17 apply. 
Then you can refresh your gradle project, pick JDK 17 once again and rebuild the project.    