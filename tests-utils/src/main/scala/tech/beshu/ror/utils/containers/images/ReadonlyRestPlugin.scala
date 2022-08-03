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
import tech.beshu.ror.utils.misc.Version

object ReadonlyRestPlugin {
  final case class Config(rorConfig: File,
                          rorPlugin: File,
                          attributes: Attributes)
  object Config {
    final case class Attributes(hotReloading: Boolean,
                                customSettingsIndex: Option[String],
                                restSslEnabled: Boolean,
                                internodeSslEnabled: Boolean,
                                isFipsEnabled: Boolean,
                                rorConfigFileName: String)
    object Attributes {
      val default: Attributes = Attributes(
        hotReloading = true,
        customSettingsIndex = None,
        restSslEnabled = true,
        internodeSslEnabled = false,
        isFipsEnabled = false,
        rorConfigFileName = "/basic/readonlyrest.yml"
      )
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
      .updateFipsDependencies(config)
      .user("root")
      .installRorPlugin(config)
  }

  override def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder = {
    builder
      .addWhen(!config.attributes.hotReloading, "readonlyrest.force_load_from_file: true")
      .addWhen(config.attributes.customSettingsIndex.isDefined, s"readonlyrest.settings_index: ${config.attributes.customSettingsIndex.get}")
      .addWhen(config.attributes.restSslEnabled, "http.type: ssl_netty4")
      .addWhen(config.attributes.internodeSslEnabled, "transport.type: ror_ssl_internode")
      .add("xpack.security.enabled: false")
  }

  override def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder = {
    builder
      .add(unboundidDebug(false))
      .add(rorHotReloading(config.attributes.hotReloading))
  }

  private def unboundidDebug(enabled: Boolean) =
    s"-Dcom.unboundid.ldap.sdk.debug.enabled=${if (enabled) true else false}"

  private def rorHotReloading(enabled: Boolean) =
    if (!enabled) "-Dcom.readonlyrest.settings.refresh.interval=0" else ""

  private implicit class InstallRorPlugin(val image: DockerImageDescription) {
    def installRorPlugin(config: Config): DockerImageDescription = {
      image
        .run(s"${esDir.toString()}/bin/elasticsearch-plugin install --batch file:///tmp/${config.rorPlugin.name}")
        .runWhen(Version.greaterOrEqualThan(esVersion, 8, 0, 0),
          command = s"${esDir.toString()}/jdk/bin/java -jar ${esDir.toString()}/plugins/readonlyrest/ror-tools.jar patch"
        )
    }
  }

  private implicit class UpdateFipsDependencies(val image: DockerImageDescription) {
    def updateFipsDependencies(config: Config): DockerImageDescription = {
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

}
