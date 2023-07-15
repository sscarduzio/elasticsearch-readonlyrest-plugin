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

import better.files._
import tech.beshu.ror.utils.containers.images.Elasticsearch.{configDir, esDir, fromResourceBy}
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes.RorConfigReloading
import tech.beshu.ror.utils.misc.Version

import scala.concurrent.duration.FiniteDuration

object ReadonlyRestPlugin {
  final case class Config(rorConfig: File,
                          rorPlugin: File,
                          attributes: Attributes)
  object Config {
    final case class Attributes(rorConfigReloading: RorConfigReloading,
                                rorCustomSettingsIndex: Option[String],
                                restSslEnabled: Boolean,
                                internodeSslEnabled: Boolean,
                                isFipsEnabled: Boolean,
                                rorConfigFileName: String)
    object Attributes {
      val default: Attributes = Attributes(
        rorConfigReloading = RorConfigReloading.Disabled,
        rorCustomSettingsIndex = None,
        restSslEnabled = true,
        internodeSslEnabled = false,
        isFipsEnabled = false,
        rorConfigFileName = "/basic/readonlyrest.yml"
      )

      sealed trait RorConfigReloading
      object RorConfigReloading {
        case object Disabled extends RorConfigReloading
        final case class Enabled(interval: FiniteDuration) extends RorConfigReloading
      }
    }
  }
}
class ReadonlyRestPlugin(esVersion: String,
                         config: Config)
  extends Elasticsearch.Plugin {

  override def updateEsImage(image: DockerImageDescription): DockerImageDescription = {
    image
      .copyFile(os.root / "tmp" / config.rorPlugin.name, config.rorPlugin)
      .copyFile(configDir / "readonlyrest.yml", config.rorConfig)
      .copyFile(configDir / "ror-keystore.jks", fromResourceBy(name = "ror-keystore.jks"))
      .copyFile(configDir / "ror-truststore.jks", fromResourceBy(name = "ror-truststore.jks"))
      .copyFile(configDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .copyFile(configDir / "elastic-certificates-cert.pem", fromResourceBy(name = "elastic-certificates-cert.pem"))
      .copyFile(configDir / "elastic-certificates-pkey.pem", fromResourceBy(name = "elastic-certificates-pkey.pem"))
      .updateFipsDependencies()
      .installRorPlugin()
  }

  override def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .add("xpack.security.enabled: false")
      .configureRorConfigAutoReloading()
      .configureRorCustomIndexSettings()
      .configureRestSsl()
      .configureTransportSsl()
  }

  override def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder = {
    builder
      .add(unboundidDebug(false))
      .add(rorReloadingInterval(config.attributes.rorConfigReloading))
  }

  private def unboundidDebug(enabled: Boolean) =
    s"-Dcom.unboundid.ldap.sdk.debug.enabled=${if (enabled) true else false}"

  private def rorReloadingInterval(rorConfigReloading: RorConfigReloading) = {
    val intervalSeconds = rorConfigReloading match {
      case RorConfigReloading.Disabled => 0
      case RorConfigReloading.Enabled(interval) => interval.toSeconds.toInt
    }
    s"-Dcom.readonlyrest.settings.refresh.interval=$intervalSeconds"
  }

  private implicit class InstallRorPlugin(val image: DockerImageDescription) {
    def installRorPlugin(): DockerImageDescription = {
      image
        .run(s"${esDir.toString()}/bin/elasticsearch-plugin install --batch file:///tmp/${config.rorPlugin.name}")
        .user("root")
        .runWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
          command = s"${esDir.toString()}/jdk/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch"
        )
        .runWhen(Version.greaterOrEqualThan(esVersion, 6, 3, 0) && Version.lowerThan(esVersion, 7, 0, 0),
          command = s"$$JAVA_HOME/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch"
        )
        .user("elasticsearch")
    }
  }

  private implicit class UpdateFipsDependencies(val image: DockerImageDescription) {
    def updateFipsDependencies(): DockerImageDescription = {
      if (!config.attributes.isFipsEnabled) image
      else {
        image
          .copyFile(configDir / "additional-permissions.policy", fromResourceBy(name = "additional-permissions.policy"))
          .copyFile(configDir / "ror-keystore.bcfks", fromResourceBy(name = "ror-keystore.bcfks"))
          .copyFile(configDir / "ror-truststore.bcfks", fromResourceBy(name = "ror-truststore.bcfks"))
          .copyFile(configDir / "elastic-certificates.bcfks", fromResourceBy(name = "elastic-certificates.bcfks"))
          .runWhen(Version.greaterOrEqualThan(esVersion, 7, 10, 0),
            s"cat ${configDir.toString()}/additional-permissions.policy >> ${esDir.toString()}/jdk/conf/security/java.policy"
          )
      }
    }
  }

  private implicit class ConfigureRorCustomIndexSettings(val builder: EsConfigBuilder) {

    def configureRorCustomIndexSettings(): EsConfigBuilder = {
      config.attributes.rorCustomSettingsIndex match {
        case Some(customRorIndex) =>
          builder.add(s"readonlyrest.settings_index: $customRorIndex")
        case None =>
          builder
      }
    }
  }

  private implicit class ConfigureRorConfigReloading(val builder: EsConfigBuilder) {

    def configureRorConfigAutoReloading(): EsConfigBuilder = {
      if(isEnabled(config.attributes.rorConfigReloading)) {
        builder
      } else {
        builder.add("readonlyrest.force_load_from_file: true")
      }
    }

    private def isEnabled(rorConfigReloading: RorConfigReloading) = rorConfigReloading match {
      case RorConfigReloading.Disabled => false
      case RorConfigReloading.Enabled(_) => true
    }
  }

  private implicit class ConfigureRestSsl(val builder: EsConfigBuilder) {

    def configureRestSsl(): EsConfigBuilder = {
      if (config.attributes.restSslEnabled) {
        builder
          .add("http.type: ssl_netty4")
          .add("readonlyrest.ssl.keystore_file: ror-keystore.jks")
          .add("readonlyrest.ssl.keystore_pass: readonlyrest")
          .add("readonlyrest.ssl.key_pass: readonlyrest")
      } else {
        builder
      }
    }
  }

  private implicit class ConfigureTransportSsl(val builder: EsConfigBuilder) {

    def configureTransportSsl(): EsConfigBuilder = {
      if (config.attributes.internodeSslEnabled) {
        builder
          .add("transport.type: ror_ssl_internode")
          .add("readonlyrest.ssl_internode.keystore_file: ror-keystore.jks")
          .add("readonlyrest.ssl_internode.keystore_pass: readonlyrest")
          .add("readonlyrest.ssl_internode.key_pass: readonlyrest")
      } else {
        builder
      }
    }
  }
}