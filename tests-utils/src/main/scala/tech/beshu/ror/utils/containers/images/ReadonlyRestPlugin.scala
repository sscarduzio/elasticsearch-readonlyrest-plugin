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
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.RorAttributes
import tech.beshu.ror.utils.misc.Version

object ReadonlyRestPlugin {
  final case class Config(esConfig: EsImage.Config,
                          rorConfig: File,
                          rorPlugin: File,
                          rorAttributes: RorAttributes)
  object Config {
    final case class RorAttributes(hotReloading: Boolean,
                                   customSettingsIndex: Option[String],
                                   restSslEnabled: Boolean,
                                   internodeSslEnabled: Boolean,
                                   isFipsEnabled: Boolean)
    object RorAttributes {
      val default: RorAttributes = RorAttributes(
        hotReloading = true,
        customSettingsIndex = None,
        restSslEnabled = true,
        internodeSslEnabled = false,
        isFipsEnabled = false
      )
    }
  }
}
trait ReadonlyRestPlugin extends EsImage {
  this: EsImage =>

  def create(config: ReadonlyRestPlugin.Config): DockerImageDescription = {
    create(
      config = config.esConfig,
      withEsConfigBuilder = updateEsConfig(config),
      withEsJavaOptsBuilder = updatesJavaOpts(config)
    )
      .copyFile(os.root / "tmp" / config.rorPlugin.name, config.rorPlugin)
      .copyFile(configDir / "readonlyrest.yml", config.rorConfig)
      .copyFile(configDir / "ror-keystore.jks", fromResourceBy(name = "ror-keystore.jks"))
      .copyFile(configDir / "ror-truststore.jks", fromResourceBy(name = "ror-truststore.jks"))
      .copyFile(configDir / "elastic-certificates.p12", fromResourceBy(name = "elastic-certificates.p12"))
      .updateFipsDependencies(config)
      .user("elasticsearch")
      .installRorPlugin(config)
  }

  private def updateEsConfig(config: ReadonlyRestPlugin.Config): EsConfigBuilder => EsConfigBuilder = builder => {
    builder
      .addWhen(!config.rorAttributes.hotReloading, "readonlyrest.force_load_from_file: true")
      .addWhen(config.rorAttributes.customSettingsIndex.isDefined, s"readonlyrest.settings_index: ${config.rorAttributes.customSettingsIndex.get}")
      .add("xpack.security.enabled: false")
      .addWhen(config.rorAttributes.restSslEnabled, "http.type: ssl_netty4")
      .addWhen(config.rorAttributes.internodeSslEnabled, "transport.type: ror_ssl_internode")
  }

  private def updatesJavaOpts(config: ReadonlyRestPlugin.Config): EsJavaOptsBuilder => EsJavaOptsBuilder = builder => {
    builder
      .add(unboundidDebug(false))
      .add(rorHotReloading(config.rorAttributes.hotReloading))
  }

  private def unboundidDebug(enabled: Boolean) =
    s"-Dcom.unboundid.ldap.sdk.debug.enabled=${if (enabled) true else false}"

  private def rorHotReloading(enabled: Boolean) =
    if (!enabled) "-Dcom.readonlyrest.settings.refresh.interval=0" else ""

  private implicit class InstallRorPlugin(val image: DockerImageDescription) {
    def installRorPlugin(config: ReadonlyRestPlugin.Config): DockerImageDescription = {
      image
        .run(s"${esDir.toString()}/bin/elasticsearch-plugin install --batch file:///tmp/${config.rorPlugin.name}")
        .runWhen(Version.greaterOrEqualThan(config.esConfig.esVersion, 8, 0, 0),
          command = s"${esDir.toString()}/jdk/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch"
        )
    }
  }

  private implicit class UpdateFipsDependencies(val image: DockerImageDescription) {
    def updateFipsDependencies(config: ReadonlyRestPlugin.Config): DockerImageDescription = {
      if (!config.rorAttributes.isFipsEnabled) image
      else {
        image
          .copyFile(configDir / "additional-permissions.policy", fromResourceBy(name = "additional-permissions.policy"))
          .copyFile(configDir / "ror-keystore.bcfks", fromResourceBy(name = "ror-keystore.bcfks"))
          .copyFile(configDir / "ror-truststore.bcfks", fromResourceBy(name = "ror-truststore.bcfks"))
          .copyFile(configDir / "elastic-certificates.bcfks", fromResourceBy(name = "elastic-certificates.bcfks"))
          .runWhen(Version.greaterOrEqualThan(config.esConfig.esVersion, 7, 10, 0),
            s"cat ${configDir.toString()}/additional-permissions.policy >> ${esDir.toString()}/jdk/conf/security/java.policy"
          )
      }
    }
  }
}
