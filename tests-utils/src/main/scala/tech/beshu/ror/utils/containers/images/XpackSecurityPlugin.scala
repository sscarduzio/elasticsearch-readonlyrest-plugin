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

import tech.beshu.ror.utils.containers.images.Elasticsearch.{configDir, esDir, fromResourceBy}
import tech.beshu.ror.utils.containers.images.XpackSecurityPlugin.Config
import tech.beshu.ror.utils.containers.images.XpackSecurityPlugin.Config.Attributes
import tech.beshu.ror.utils.misc.Version

object XpackSecurityPlugin {

  final case class Config(attributes: Attributes)
  object Config {
    final case class Attributes(restSslEnabled: Boolean,
                                internodeSslEnabled: Boolean)
    object Attributes {
      val default: Attributes = Attributes(
        restSslEnabled = true,
        internodeSslEnabled = true
      )
    }
  }
}
class XpackSecurityPlugin(esVersion: String,
                          config: Config)
  extends Elasticsearch.Plugin {

  override def updateEsImage(image: DockerImageDescription): DockerImageDescription = {
    image
      .copyFile(configDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .copyFile(configDir / "elastic-certificates-cert.pem", fromResourceBy(name = "elastic-certificates-cert.pem"))
      .copyFile(configDir / "elastic-certificates-pkey.pem", fromResourceBy(name = "elastic-certificates-pkey.pem"))
      .configureKeystore()
  }

  override def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .add("xpack.security.enabled: true")
      .add("xpack.ml.enabled: true")
      .configureRestSsl()
      .configureTransportSsl()
  }

  override def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder = builder

  private implicit class ConfigureRestSsl(val builder: EsConfigBuilder) {

    def configureRestSsl(): EsConfigBuilder = {
      if (config.attributes.restSslEnabled) {
        builder
          .add("xpack.security.http.ssl.enabled: true")
          .add("xpack.security.http.ssl.verification_mode: none")
          .add("xpack.security.http.ssl.client_authentication: none")
          .add("xpack.security.http.ssl.keystore.path: elastic-certificates.p12")
          .add("xpack.security.http.ssl.truststore.path: elastic-certificates.p12")
      } else {
        builder
      }
    }
  }

  private implicit class ConfigureTransportSsl(val builder: EsConfigBuilder) {

    def configureTransportSsl(): EsConfigBuilder = {
      if (config.attributes.internodeSslEnabled) {
        builder
          .add("xpack.security.transport.ssl.enabled: true")
          .add("xpack.security.transport.ssl.verification_mode: none")
          .add("xpack.security.transport.ssl.client_authentication: none")
          .add("xpack.security.transport.ssl.keystore.path: elastic-certificates.p12")
          .add("xpack.security.transport.ssl.truststore.path: elastic-certificates.p12")
      } else {
        builder
      }
    }
  }

  private implicit class ConfigureKeystore(val image: DockerImageDescription) {

    def configureKeystore(): DockerImageDescription = {
      image
        .run(createKeystoreCommand)
        .runWhen(
          config.attributes.internodeSslEnabled,
          addToKeystoreCommand(key = "xpack.security.transport.ssl.keystore.secure_password", value = "readonlyrest")
        )
        .runWhen(
          config.attributes.internodeSslEnabled,
          addToKeystoreCommand(key = "xpack.security.transport.ssl.truststore.secure_password", value = "readonlyrest")
        )
        .runWhen(
          config.attributes.restSslEnabled,
          addToKeystoreCommand(key = "xpack.security.http.ssl.keystore.secure_password", value = "readonlyrest")
        )
        .runWhen(
          config.attributes.restSslEnabled,
          addToKeystoreCommand(key = "xpack.security.http.ssl.truststore.secure_password", value = "readonlyrest")
        )
        .runWhen(
          Version.greaterOrEqualThan(esVersion, 6, 6, 0),
          addToKeystoreCommand(key = "bootstrap.password", value = "elastic")
        )
    }

    private def createKeystoreCommand = s"${esDir.toString()}/bin/elasticsearch-keystore create"

    private def addToKeystoreCommand(key: String, value: String) = {
      s"printf '$value\\n' | ${esDir.toString()}/bin/elasticsearch-keystore add $key"
    }
  }
}
