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

import better.files.File
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.{Attributes, InternodeSsl, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.Enabled

import scala.concurrent.duration.FiniteDuration

object ReadonlyRestWithEnabledXpackSecurityPlugin {
  final case class Config(rorConfig: File,
                          rorPlugin: File,
                          attributes: Attributes)
  object Config {
    final case class Attributes(rorConfigReloading: Enabled[FiniteDuration],
                                rorCustomSettingsIndex: Option[String],
                                restSsl: Enabled[RestSsl],
                                internodeSsl: Enabled[InternodeSsl],
                                rorConfigFileName: String)
    object Attributes {
      val default: Attributes = Attributes(
        rorConfigReloading = ReadonlyRestPlugin.Config.Attributes.default.rorConfigReloading,
        rorCustomSettingsIndex = ReadonlyRestPlugin.Config.Attributes.default.rorCustomSettingsIndex,
        restSsl = if(XpackSecurityPlugin.Config.Attributes.default.restSslEnabled) Enabled.Yes(RestSsl.Xpack) else Enabled.No,
        internodeSsl = if(XpackSecurityPlugin.Config.Attributes.default.internodeSslEnabled) Enabled.Yes(InternodeSsl.Xpack) else Enabled.No,
        rorConfigFileName = "/basic/readonlyrest.yml"
      )
    }

    sealed trait RestSsl
    object RestSsl {
      case object Xpack extends RestSsl
      final case class Ror(rorrestSsl: ReadonlyRestPlugin.Config.RestSsl) extends RestSsl
    }

    sealed trait InternodeSsl
    object InternodeSsl {
      case object Xpack extends InternodeSsl
      final case class Ror(rorInternodeSsl: ReadonlyRestPlugin.Config.InternodeSsl) extends InternodeSsl
    }
  }
}

class ReadonlyRestWithEnabledXpackSecurityPlugin(esVersion: String,
                                                 config: Config,
                                                 performPatching: Boolean)
  extends Elasticsearch.Plugin {

  private val readonlyRestPlugin = new ReadonlyRestPlugin(esVersion, createRorConfig(), performPatching)
  private val xpackSecurityPlugin = new XpackSecurityPlugin(esVersion, createXpackSecurityConfig())

  override def updateEsImage(image: DockerImageDescription): DockerImageDescription = {
    (readonlyRestPlugin :: xpackSecurityPlugin :: Nil)
      .foldLeft(image) { case (currentImage, plugin) =>
        plugin.updateEsImage(currentImage)
      }
  }

  override def updateEsConfigBuilder(builder: EsConfigBuilder): EsConfigBuilder = {
    (readonlyRestPlugin :: xpackSecurityPlugin :: Nil)
      .foldLeft(builder) { case (currentImage, plugin) =>
        plugin.updateEsConfigBuilder(currentImage)
      }
      .remove("xpack.security.enabled: false")
  }

  override def updateEsJavaOptsBuilder(builder: EsJavaOptsBuilder): EsJavaOptsBuilder = {
    (readonlyRestPlugin :: xpackSecurityPlugin :: Nil)
      .foldLeft(builder) { case (currentImage, plugin) =>
        plugin.updateEsJavaOptsBuilder(currentImage)
      }
  }

  private def createRorConfig() = {
    ReadonlyRestPlugin.Config(
      rorConfig = config.rorConfig,
      rorPlugin = config.rorPlugin,
      attributes = ReadonlyRestPlugin.Config.Attributes(
        config.attributes.rorConfigReloading,
        config.attributes.rorCustomSettingsIndex,
        restSsl = createRorRestSsl(),
        internodeSsl = createRorInternodeSsl(),
        config.attributes.rorConfigFileName
      )
    )
  }

  private def createRorRestSsl() = {
    config.attributes.restSsl match {
      case Enabled.Yes(RestSsl.Ror(rorRestSsl)) => Enabled.Yes(rorRestSsl)
      case Enabled.Yes(RestSsl.Xpack) => Enabled.No
      case Enabled.No => Enabled.No
    }
  }

  private def createRorInternodeSsl() = {
    config.attributes.internodeSsl match {
      case Enabled.Yes(InternodeSsl.Ror(rorInternodeSsl)) => Enabled.Yes(rorInternodeSsl)
      case Enabled.Yes(InternodeSsl.Xpack) => Enabled.No
      case Enabled.No => Enabled.No
    }
  }

  private def createXpackSecurityConfig() = {
    XpackSecurityPlugin.Config(
      XpackSecurityPlugin.Config.Attributes(
        restSslEnabled = config.attributes.restSsl match {
          case Enabled.Yes(RestSsl.Xpack) => true
          case Enabled.Yes(RestSsl.Ror(_)) => false
          case Enabled.No => false
        },
        internodeSslEnabled = config.attributes.internodeSsl match {
          case Enabled.Yes(InternodeSsl.Xpack) => true
          case Enabled.Yes(InternodeSsl.Ror(_)) => false
          case Enabled.No => false
        }
      )
    )
  }
}

