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

import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin.PluginInstallationSteps
import tech.beshu.ror.utils.containers.images.Elasticsearch.Plugin.PluginInstallationSteps.emptyPluginInstallationSteps
import tech.beshu.ror.utils.containers.images.Elasticsearch.{esDir, fromResourceBy}
import tech.beshu.ror.utils.containers.images.XpackSecurityPlugin.Config
import tech.beshu.ror.utils.containers.images.XpackSecurityPlugin.Config.{Attributes, ClientAuthentication}
import tech.beshu.ror.utils.misc.Version

object XpackSecurityPlugin {

  final case class Config(attributes: Attributes)

  object Config {

    final case class Attributes(
        restSslEnabled: Boolean,
        internodeSslEnabled: Boolean,
        restSslClientAuthentication: ClientAuthentication = ClientAuthentication.None
    )

    object Attributes {

      val default: Attributes = Attributes(
        restSslEnabled = true,
        internodeSslEnabled = true
      )

    }

    /** What the HTTP layer asks of callers. Anything but `None` also swaps the HTTP truststore for the
      * PKI one, so that the test client certificates are trusted.
      */
    sealed abstract class ClientAuthentication(val configValue: String)

    object ClientAuthentication {
      case object None extends ClientAuthentication("none")
      case object Optional extends ClientAuthentication("optional")
      case object Required extends ClientAuthentication("required")
    }

  }

}

class XpackSecurityPlugin(esVersion: String, config: Config) extends Elasticsearch.Plugin {

  override def installationSteps(esConfig: Elasticsearch.Config): PluginInstallationSteps = {
    emptyPluginInstallationSteps
      .copyFile(esConfig.esConfigDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .copyFile(esConfig.esConfigDir / "pki-truststore.jks", fromResourceBy(name = "pki/pki-truststore.jks"))
      .copyFile(
        esConfig.esConfigDir / "elastic-certificates-cert.pem",
        fromResourceBy(name = "elastic-certificates-cert.pem")
      )
      .copyFile(
        esConfig.esConfigDir / "elastic-certificates-pkey.pem",
        fromResourceBy(name = "elastic-certificates-pkey.pem")
      )
      .configureKeystore(esConfig)
  }

  override def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .add("xpack.security.enabled: true")
      .configureRestSsl()
      .configureTransportSsl()
  }

  override def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder = builder

  private implicit class ConfigureRestSsl(val builder: EsConfigBuilder) {

    def configureRestSsl(): EsConfigBuilder = {
      if (config.attributes.restSslEnabled) {
        val clientAuthentication = config.attributes.restSslClientAuthentication
        builder
          .add("xpack.security.http.ssl.enabled: true")
          .add("xpack.security.http.ssl.verification_mode: none")
          .add(s"xpack.security.http.ssl.client_authentication: ${clientAuthentication.configValue}")
          .add("xpack.security.http.ssl.keystore.path: elastic-certificates.p12")
          .add(clientAuthentication match {
            case ClientAuthentication.None => "xpack.security.http.ssl.truststore.path: elastic-certificates.p12"
            case _                         => "xpack.security.http.ssl.truststore.path: pki-truststore.jks"
          })
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
          .add("xpack.security.transport.ssl.verification_mode: certificate")
          .add("xpack.security.transport.ssl.client_authentication: none")
          .add("xpack.security.transport.ssl.keystore.path: elastic-certificates.p12")
          .add("xpack.security.transport.ssl.truststore.path: elastic-certificates.p12")
      } else {
        builder
      }
    }

  }

  private implicit class ConfigureKeystore(val pluginInstallationSteps: PluginInstallationSteps) {

    def configureKeystore(esConfig: Elasticsearch.Config): PluginInstallationSteps = {
      pluginInstallationSteps
        .run(
          linuxCommand = createKeystoreCommand(esConfig),
          windowsCommand = createKeystoreCommand(esConfig),
        )
        .runWhen(
          condition = config.attributes.internodeSslEnabled,
          linuxCommand = addToKeystoreLinuxCommand(
            esConfig,
            key = "xpack.security.transport.ssl.keystore.secure_password",
            value = "readonlyrest"
          ),
          windowsCommand = addToKeystoreWindowsCommand(
            esConfig,
            key = "xpack.security.transport.ssl.keystore.secure_password",
            value = "readonlyrest"
          ),
        )
        .runWhen(
          condition = config.attributes.internodeSslEnabled,
          linuxCommand = addToKeystoreLinuxCommand(
            esConfig,
            key = "xpack.security.transport.ssl.truststore.secure_password",
            value = "readonlyrest"
          ),
          windowsCommand = addToKeystoreWindowsCommand(
            esConfig,
            key = "xpack.security.transport.ssl.truststore.secure_password",
            value = "readonlyrest"
          ),
        )
        .runWhen(
          condition = config.attributes.restSslEnabled,
          linuxCommand = addToKeystoreLinuxCommand(
            esConfig,
            key = "xpack.security.http.ssl.keystore.secure_password",
            value = "readonlyrest"
          ),
          windowsCommand = addToKeystoreWindowsCommand(
            esConfig,
            key = "xpack.security.http.ssl.keystore.secure_password",
            value = "readonlyrest"
          ),
        )
        .runWhen(
          condition = config.attributes.restSslEnabled,
          linuxCommand = addToKeystoreLinuxCommand(
            esConfig,
            key = "xpack.security.http.ssl.truststore.secure_password",
            value = "readonlyrest"
          ),
          windowsCommand = addToKeystoreWindowsCommand(
            esConfig,
            key = "xpack.security.http.ssl.truststore.secure_password",
            value = "readonlyrest"
          ),
        )
        .runWhen(
          condition = Version.greaterOrEqualThan(esVersion, 6, 6, 0),
          linuxCommand = addToKeystoreLinuxCommand(esConfig, key = "bootstrap.password", value = "elastic"),
          windowsCommand = addToKeystoreWindowsCommand(esConfig, key = "bootstrap.password", value = "elastic"),
        )
    }

    private def createKeystoreCommand(esConfig: Elasticsearch.Config) =
      s"${esConfig.esDir.toString()}/bin/elasticsearch-keystore create"

    private def addToKeystoreLinuxCommand(esConfig: Elasticsearch.Config, key: String, value: String) = {
      s"printf '$value\\n' | ${esConfig.esDir.toString()}/bin/elasticsearch-keystore add --force $key"
    }

    private def addToKeystoreWindowsCommand(esConfig: Elasticsearch.Config, key: String, value: String) = {
      s"""cmd /c "echo|set /p="$value" | "${esConfig.esDir}\\bin\\elasticsearch-keystore.bat" add --force $key" """
    }

  }

}
