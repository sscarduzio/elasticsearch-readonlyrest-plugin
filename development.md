# Development setup for readonly rest plugin
These instructions explain how to build and test the plugin.
## Clone readonly rest plugin
`git clone https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin.git /me/elasticsearch-readonlyrest-plugin`
For this example, we cloned the source code under a short path: the `/me` directory.
## Set proper JDK version
Current version of ROR requires JDK 17. 
## Building plugin using Gradle for concrete ES version:

### Using Gradle
* `./gradlew clean buildRorPlugin '-PesVersion=[ES_VERSION]'` 
* eg. `./gradlew clean buildRorPlugin '-PesVersion=7.2.0'`
* ROR plugin binaries can be found in `es70x/build/distributions/`

**⚠️Required tools:**
* Java 17
* Gradle

### Using Docker
* `./docker-based-builder/build.sh [ES_VERSION_1] [ES_VERSION_1] ... [ES_VERSION_N]`
* eg. `./docker-based-builder/build.sh 8.2.0 8.2.1`
* ROR plugin binaries can be found in `docker-envs/build-ror-in-docker/builds`

**⚠️Required tools:**
* Docker

## Running tests
* unit tests: `./gradlew core:test`
* integration tests for specific module (at the moment we have two modules with integration tests): 
  * `./gradlew integration-tests:test -PesModule=es70x`
* e2e (Cypress) tests: they live in the `readonlyrest-e2e-tests` repo and drive Kibana with both
  ROR plugins. CI runs them on every non-draft PR — see [ci/README.md](ci/README.md#e2e-tests).
## Adding license headers to newly created files:
* `./gradlew licenseFormatMain`
* `./gradlew licenseFormatTest`

## Code formatting:
Format all sources:
* `./gradlew formatCode`

Check formatting without modifying files (used in CI):
* `./gradlew formatCodeCheck`

The pre-commit hook runs formatting automatically on staged `.scala` and `.java` files before each commit.
## Testing with LDAP
If you need to setup local LDAP you can use LDAPServer defined in our tests sources: `tech.beshu.ror.utils.ldap.LDAPServer`
