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

import better.files.*
import tech.beshu.ror.utils.containers.images.Elasticsearch.{configDir, esDir, fromResourceBy}
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.{Attributes, InternodeSsl, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.{Enabled, SourceFile}
import tech.beshu.ror.utils.misc.Version

import scala.concurrent.duration.FiniteDuration

object ReadonlyRestPlugin {
  final case class Config(rorConfig: File,
                          rorPlugin: File,
                          rorProperties: File,
                          rorSecurityPolicy: File,
                          attributes: Attributes)
  object Config {
    final case class Attributes(rorConfigReloading: Enabled[FiniteDuration],
                                rorCustomSettingsIndex: Option[String],
                                restSsl: Enabled[RestSsl],
                                internodeSsl: Enabled[InternodeSsl],
                                rorConfigFileName: String)
    object Attributes {
      val default: Attributes = Attributes(
        rorConfigReloading = Enabled.No,
        rorCustomSettingsIndex = None,
        restSsl = Enabled.Yes(RestSsl.Ror(SourceFile.EsFile)),
        internodeSsl = Enabled.No,
        rorConfigFileName = "/basic/readonlyrest.yml"
      )
    }

    sealed trait RestSsl
    object RestSsl {
      final case class Ror(sourceFile: SourceFile) extends RestSsl
      final case class RorFips(sourceFile: SourceFile) extends RestSsl
    }

    sealed trait InternodeSsl
    object InternodeSsl {
      final case class Ror(sourceFile: SourceFile) extends InternodeSsl
      final case class RorFips(sourceFile: SourceFile) extends InternodeSsl
    }
  }
}
class ReadonlyRestPlugin(esVersion: String,
                         config: Config,
                         performInstallation: Boolean)
  extends Elasticsearch.Plugin {

  override def updateEsImage(image: DockerImageDescription): DockerImageDescription = {
    val withoutInstallation = image
      .copyFile(os.root / "tmp" / config.rorPlugin.name, config.rorPlugin)
      .copyFile(esDir / "tmp" / config.rorProperties.name, config.rorProperties)
      .copyFile(esDir / "tmp" / config.rorSecurityPolicy.name, config.rorSecurityPolicy)
      .copyFile(configDir / "ror-keystore.jks", fromResourceBy(name = "ror-keystore.jks"))
      .copyFile(configDir / "ror-truststore.jks", fromResourceBy(name = "ror-truststore.jks"))
      .copyFile(configDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .copyFile(configDir / "elastic-certificates-cert.pem", fromResourceBy(name = "elastic-certificates-cert.pem"))
      .copyFile(configDir / "elastic-certificates-pkey.pem", fromResourceBy(name = "elastic-certificates-pkey.pem"))
      .updateFipsDependencies()

    if (performInstallation) {
      withoutInstallation
        .copyFile(configDir / "readonlyrest.yml", config.rorConfig)
        .installRorPlugin()
    } else {
      withoutInstallation
    }
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
      .add(rorReloadingInterval())
  }

  private def unboundidDebug(enabled: Boolean) =
    s"-Dcom.unboundid.ldap.sdk.debug.enabled=${if (enabled) true else false}"

  private def rorReloadingInterval() = {
    val intervalSeconds = config.attributes.rorConfigReloading match {
      case Enabled.No => 0
      case Enabled.Yes(interval) => interval.toSeconds.toInt
    }
    s"-Dcom.readonlyrest.settings.refresh.interval=$intervalSeconds"
  }

  private implicit class InstallRorPlugin(val image: DockerImageDescription) {
    def installRorPlugin(): DockerImageDescription = {
      image
        .run(s"${esDir.toString()}/bin/elasticsearch-plugin install --batch file:///tmp/${config.rorPlugin.name}")
        .user("root")
        .runWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
          command = s"${esDir.toString()}/jdk/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch --I-understand-and-accept-ES-patching yes"
        )
        .runWhen(Version.greaterOrEqualThan(esVersion, 6, 5, 0) && Version.lowerThan(esVersion, 7, 0, 0),
          command = s"$$JAVA_HOME/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch --I-understand-and-accept-ES-patching yes"
        )
        .user("elasticsearch")
    }
  }

  private implicit class UpdateFipsDependencies(val image: DockerImageDescription) {
    def updateFipsDependencies(): DockerImageDescription = {
      if (isFibsEnabled) {
        image
          .copyFile(configDir / "additional-permissions.policy", fromResourceBy(name = "additional-permissions.policy"))
          .copyFile(configDir / "ror-keystore.bcfks", fromResourceBy(name = "ror-keystore.bcfks"))
          .copyFile(configDir / "ror-truststore.bcfks", fromResourceBy(name = "ror-truststore.bcfks"))
          .copyFile(configDir / "elastic-certificates.bcfks", fromResourceBy(name = "elastic-certificates.bcfks"))
          .runWhen(Version.greaterOrEqualThan(esVersion, 7, 10, 0),
            s"cat ${configDir.toString()}/additional-permissions.policy >> ${esDir.toString()}/jdk/conf/security/java.policy"
          )
      }
      else {
        image
      }
    }

    private def isFibsEnabled = {
      (config.attributes.restSsl match {
        case Enabled.Yes(RestSsl.RorFips(_)) => true
        case Enabled.Yes(RestSsl.Ror(_)) => false
        case Enabled.No => false
      }) ||
        (config.attributes.internodeSsl match {
          case Enabled.Yes(InternodeSsl.RorFips(_)) => true
          case Enabled.Yes(InternodeSsl.Ror(_)) => false
          case Enabled.No => false
        })
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
      config.attributes.rorConfigReloading match {
        case Enabled.Yes(_) =>
          builder
        case Enabled.No =>
          builder.add("readonlyrest.force_load_from_file: true")
      }
    }
  }

  private implicit class ConfigureRestSsl(val builder: EsConfigBuilder) {

    def configureRestSsl(): EsConfigBuilder = {
      config.attributes.restSsl match {
        case Enabled.Yes(RestSsl.Ror(SourceFile.EsFile)) =>
          builder
            .add("http.type: ssl_netty4")
            .add("readonlyrest.ssl.keystore_file: ror-keystore.jks")
            .add("readonlyrest.ssl.keystore_pass: readonlyrest")
            .add("readonlyrest.ssl.key_pass: readonlyrest")
        case Enabled.Yes(RestSsl.RorFips(SourceFile.EsFile)) =>
          builder
            .add("http.type: ssl_netty4")
            .add("readonlyrest.fips_mode: SSL_ONLY")
            .add("readonlyrest.ssl.keystore_file: elastic-certificates.bcfks")
            .add("readonlyrest.ssl.keystore_pass: readonlyrest")
            .add("readonlyrest.ssl.key_pass: readonlyrest")
            .add("truststore_file: elastic-certificates.bcfks")
            .add("truststore_pass: readonlyrest")
            .add("key_pass: readonlyrest")
        case Enabled.Yes(RestSsl.Ror(SourceFile.RorFile)) =>
          builder
            .add("http.type: ssl_netty4")
        case Enabled.Yes(RestSsl.RorFips(SourceFile.RorFile)) =>
          builder
            .add("http.type: ssl_netty4")
        case Enabled.No =>
          builder
      }
    }
  }

  private implicit class ConfigureTransportSsl(val builder: EsConfigBuilder) {

    def configureTransportSsl(): EsConfigBuilder = {
      config.attributes.internodeSsl match {
        case Enabled.Yes(InternodeSsl.Ror(SourceFile.EsFile)) =>
          builder
            .add("transport.type: ror_ssl_internode")
            .add("readonlyrest.ssl_internode.keystore_file: ror-keystore.jks")
            .add("readonlyrest.ssl_internode.keystore_pass: readonlyrest")
            .add("readonlyrest.ssl_internode.key_pass: readonlyrest")
            .add("readonlyrest.truststore_file: ror-keystore.jks")
            .add("readonlyrest.truststore_pass: readonlyrest")
            .add("readonlyrest.certificate_verification: true")
        case Enabled.Yes(InternodeSsl.RorFips(SourceFile.EsFile)) =>
          builder
            .add("transport.type: ror_ssl_internode")
            .add("readonlyrest.fips_mode: SSL_ONLY")
            .add("readonlyrest.ssl_internode.keystore_file: ror-keystore.bcfks")
            .add("readonlyrest.ssl_internode.keystore_pass: readonlyrest")
            .add("readonlyrest.ssl_internode.key_pass: readonlyrest")
            .add("truststore_file: ror-truststore.bcfks") // todo: no "readonlyrest" prefix?
            .add("truststore_pass: readonlyrest")
            .add("certificate_verification: true")
        case Enabled.Yes(InternodeSsl.Ror(SourceFile.RorFile)) =>
          builder
            .add("transport.type: ror_ssl_internode")
        case Enabled.Yes(InternodeSsl.RorFips(SourceFile.RorFile)) =>
          builder
            .add("transport.type: ror_ssl_internode")
        case Enabled.No =>
          builder
      }
    }
  }
}
