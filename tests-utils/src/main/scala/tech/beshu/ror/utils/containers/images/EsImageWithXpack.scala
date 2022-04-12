/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.utils.containers.images

import tech.beshu.ror.utils.misc.Version

class EsImageWithXpack extends EsImage {

  override def create(config: EsImage.Config): DockerImageDescription = {
    super
      .create(config, updateEsConfig, identity)
      .copyFile(configDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .configureKeystore(config)
      .configureCustomEntrypoint(config)
  }

  private def updateEsConfig(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .add("xpack.security.enabled: true")
      .add("xpack.ml.enabled: false")
      .add("xpack.security.transport.ssl.enabled: true")
      .add("xpack.security.transport.ssl.verification_mode: none")
      .add("xpack.security.transport.ssl.client_authentication: none")
      .add("xpack.security.transport.ssl.keystore.path: elastic-certificates.p12")
      .add("xpack.security.transport.ssl.truststore.path: elastic-certificates.p12")
  }

  private implicit class ConfigureKeystore(val image: DockerImageDescription) {

    def configureKeystore(config: EsImage.Config): DockerImageDescription = {
      image
        .run(s"${esDir.toString()}/bin/elasticsearch-keystore create")
        .run(s"printf 'readonlyrest\\n' | ${esDir.toString()}/bin/elasticsearch-keystore add xpack.security.transport.ssl.keystore.secure_password")
        .run(s"printf 'readonlyrest\\n' | ${esDir.toString()}/bin/elasticsearch-keystore add xpack.security.transport.ssl.truststore.secure_password")
        .runWhen(Version.greaterOrEqualThan(config.esVersion, 6, 6, 0),
          s"printf 'elastic\\n' | ${esDir.toString()}/bin/elasticsearch-keystore add bootstrap.password"
        )
    }
  }

  private implicit class ConfigureCustomEntrypoint(val image: DockerImageDescription) {

    def configureCustomEntrypoint(config: EsImage.Config): DockerImageDescription = {
      val entrypointScriptFile = esDir / "xpack-setup-entry.sh"
      image
        .runWhen(Version.greaterOrEqualThan(config.esVersion, 6, 0, 0),
          command = s"echo '/usr/local/bin/docker-entrypoint.sh &' > ${entrypointScriptFile.toString()}",
          orElseCommand = s"echo '${esDir.toString()}/bin/es-docker &' > ${entrypointScriptFile.toString()}"
        )
        // Time needed to bootstrap cluster as elasticsearch-setup-passwords has to be run after cluster is ready
        .run(s"echo 'sleep 15' >> ${entrypointScriptFile.toString()}")
        .run(s"""echo 'export ES_JAVA_OPTS="-Xms1g -Xmx1g -Djava.security.egd=file:/dev/./urandoms"' >> ${entrypointScriptFile.toString()}""")
        .run(s"""echo 'echo "Trying to add assign superuser role to elastic"' >> ${entrypointScriptFile.toString()}""")
        .runWhen(Version.greaterOrEqualThan(config.esVersion, 6, 7, 0),
          s"""echo "for i in {1..30}; do curl -X POST -u elastic:elastic "http://localhost:9200/_security/user/admin?pretty" -H 'Content-Type: application/json' -d'{"password" : "container", "roles" : ["superuser"]}'; sleep 2; done" >> ${entrypointScriptFile.toString()}"""
        )
        .run(s"echo 'wait' >> ${entrypointScriptFile.toString()}")
        .run(s"chmod +x ${entrypointScriptFile.toString()}")
        .setEntrypoint(entrypointScriptFile)
    }
  }

}
