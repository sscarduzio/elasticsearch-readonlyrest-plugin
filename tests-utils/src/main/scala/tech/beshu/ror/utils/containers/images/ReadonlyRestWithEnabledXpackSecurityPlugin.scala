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
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes.RorConfigReloading
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.{Attributes, Enabled, InternodeSsl, RestSsl}

object ReadonlyRestWithEnabledXpackSecurityPlugin {
  final case class Config(rorConfig: File,
                          rorPlugin: File,
                          attributes: Attributes)
  object Config {
    final case class Attributes(rorConfigReloading: RorConfigReloading,
                                rorCustomSettingsIndex: Option[String],
                                restSsl: Enabled[RestSsl],
                                internodeSsl: Enabled[InternodeSsl],
                                rorConfigFileName: String)
    object Attributes {
      val default: Attributes = Attributes(
        rorConfigReloading = ReadonlyRestPlugin.Config.Attributes.default.rorConfigReloading,
        rorCustomSettingsIndex = ReadonlyRestPlugin.Config.Attributes.default.customSettingsIndex,
        restSsl = if(ReadonlyRestPlugin.Config.Attributes.default.restSslEnabled) Enabled.Yes(RestSsl.Ror) else Enabled.No,
        internodeSsl = if(ReadonlyRestPlugin.Config.Attributes.default.internodeSslEnabled) Enabled.Yes(InternodeSsl.Ror) else Enabled.No,
        rorConfigFileName = "/basic/readonlyrest.yml"
      )
    }

    sealed trait Enabled[+T]
    object Enabled {
      final case class Yes[T](value: T) extends Enabled[T]
      case object No extends Enabled[Nothing]
    }

    sealed trait RestSsl
    object RestSsl {
      case object Ror extends RestSsl
      case object RorFips extends RestSsl
    }

    sealed trait InternodeSsl
    object InternodeSsl {
      case object Es extends InternodeSsl
      case object Ror extends InternodeSsl
      case object RorFips extends InternodeSsl
    }
  }
}

class ReadonlyRestWithEnabledXpackSecurityPlugin(esVersion: String,
                                                 config: Config)
  extends Elasticsearch.Plugin {

  private val readonlyRestPlugin = new ReadonlyRestPlugin(esVersion, createRorConfig())
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
        isRorRestSslEnabled,
        isRorInternodeSslEnabled,
        isRorFibsEnabled,
        config.attributes.rorConfigFileName
      )
    )
  }

  private def isRorRestSslEnabled = config.attributes.restSsl match {
    case Enabled.Yes(RestSsl.Ror) => true
    case Enabled.Yes(RestSsl.RorFips) => true
    case Enabled.No => false
  }

  private def isRorInternodeSslEnabled = config.attributes.internodeSsl match {
    case Enabled.Yes(InternodeSsl.Es) => false
    case Enabled.Yes(InternodeSsl.Ror) => true
    case Enabled.Yes(InternodeSsl.RorFips) => true
    case Enabled.No => false
  }

  private def isRorFibsEnabled = {
    (config.attributes.restSsl match {
      case Enabled.Yes(RestSsl.Ror) => false
      case Enabled.Yes(RestSsl.RorFips) => true
      case Enabled.No => false
    }) || (
      config.attributes.internodeSsl match {
        case Enabled.Yes(InternodeSsl.Es) => false
        case Enabled.Yes(InternodeSsl.Ror) => false
        case Enabled.Yes(InternodeSsl.RorFips) => true
        case Enabled.No => false
      }
      )
  }

  private def createXpackSecurityConfig() = {
    XpackSecurityPlugin.Config(
      XpackSecurityPlugin.Config.Attributes(
        internodeSslEnabled = config.attributes.internodeSsl match {
          case Enabled.Yes(InternodeSsl.Es) => true
          case Enabled.Yes(InternodeSsl.Ror) => false
          case Enabled.Yes(InternodeSsl.RorFips) => false
          case Enabled.No => false
        }
      )
    )
  }
}

