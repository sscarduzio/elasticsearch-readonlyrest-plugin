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
package tech.beshu.ror.settings.es

import better.files.File
import cats.data.NonEmptyList
import eu.timepit.refined.types.all.NonEmptyString
import monix.eval.Task
import squants.information.{Information, Megabytes}
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsFile, RorSettingsIndex}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError

final case class RorSettingsSourcesConfig(settingsIndex: RorSettingsIndex,
                                          settingsFile: RorSettingsFile,
                                          settingsMaxSize: Information)

object RorSettingsSourcesConfig extends YamlFileBasedSettingsLoaderSupport {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, RorSettingsSourcesConfig]] = {
    implicit val rorBootSettingsDecoder: YamlLeafOrPropertyDecoder[RorSettingsSourcesConfig] =
      decoders.rorSettingsSourcesConfigDecoder(systemContext, esEnv)
    loadSetting[RorSettingsSourcesConfig](esEnv, "ROR settings source settings")
  }

  private object decoders {

    object defaults {
      val rorSettingsMaxSize: Information = Megabytes(3)
    }

    object consts {
      val rorSection: NonEmptyString = NonEmptyString.unsafeFrom("readonlyrest")
      val settingsSection: NonEmptyString = NonEmptyString.unsafeFrom("settings")
      val indexNameKey: NonEmptyString = NonEmptyString.unsafeFrom("index_name")
      val filePathKey: NonEmptyString = NonEmptyString.unsafeFrom("file_path")
      val maxSizeKey: NonEmptyString = NonEmptyString.unsafeFrom("max_size")
    }

    def rorSettingsSourcesConfigDecoder(systemContext: SystemContext,
                                        esEnv: EsEnv): YamlLeafOrPropertyDecoder[RorSettingsSourcesConfig] = {
      for {
        settingsIndexName <- settingsIndexNameDecoder(systemContext)
        settingsFilePath <- settingsFileDecoder(systemContext)
        settingsMaxSize <- settingsMaxSizeDecoder(systemContext)
      } yield RorSettingsSourcesConfig(
        settingsIndexName.getOrElse(RorSettingsIndex.default),
        settingsFilePath.getOrElse(RorSettingsFile.default(esEnv)),
        settingsMaxSize.getOrElse(defaults.rorSettingsMaxSize)
      )
    }

    private def settingsIndexNameDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, RorSettingsIndex] = { str =>
        NonEmptyString
          .from(str)
          .map(str => RorSettingsIndex(IndexName.Full(str)))
          .left.map(error => ???) // todo:
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.settingsSection, consts.indexNameKey),
        creator = creator
      )
    }

    private def settingsFileDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, RorSettingsFile] = { str =>
        NonEmptyString
          .from(str)
          .map(str => RorSettingsFile(File(str.value)))
          .left.map(error => ???) // todo:
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.settingsSection, consts.filePathKey),
        creator = creator
      )
    }

    private def settingsMaxSizeDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, Information] = { str =>
        Information
          .parseString(str)
          .toEither
          .left.map(ex => ???) // todo:
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.settingsSection, consts.maxSizeKey),
        creator = creator
      )
    }

  }
}