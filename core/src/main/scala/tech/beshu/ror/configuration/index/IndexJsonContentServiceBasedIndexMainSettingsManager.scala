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
package tech.beshu.ror.configuration.index

import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorSettingsIndex
import tech.beshu.ror.configuration.index.IndexJsonContentServiceBasedIndexMainSettingsManager.Const
import tech.beshu.ror.configuration.index.IndexSettingsManager.{LoadingIndexSettingsError, SavingIndexSettingsError}
import tech.beshu.ror.configuration.{RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError.*
import tech.beshu.ror.configuration.index.IndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.ParsingError
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}

final class IndexJsonContentServiceBasedIndexMainSettingsManager(override val settingsIndex: RorSettingsIndex,
                                                                 override val rorSettingsYamlParser: RawRorSettingsYamlParser,
                                                                 indexJsonContentService: IndexJsonContentService)
  extends IndexSettingsManager[RawRorSettings]
  with Logging {

  override def load(): Task[Either[Error[LoadingIndexSettingsError], RawRorSettings]] = {
    indexJsonContentService
      .sourceOf(settingsIndex.index, Const.id)
      .flatMap {
        case Right(source) =>
          source
            .find(_._1 == Const.settingsKey)
            .map { case (_, rorYamlString) =>
              rorSettingsYamlParser
                .fromString(rorYamlString)
                .map(_.left.map(ParsingError.apply))
            }
            .getOrElse {
              settingsLoaderError(UnknownStructureOfIndexDocument)
            }
        case Left(CannotReachContentSource) =>
          settingsLoaderError(IndexNotExist)
        case Left(ContentNotFound) =>
          settingsLoaderError(IndexNotExist)
      }
  }

  override def save(settings: RawRorSettings): Task[Either[SavingIndexSettingsError, Unit]] = {
    indexJsonContentService
      .saveContent(
        settingsIndex.index,
        Const.id,
        Map(Const.settingsKey -> settings.raw)
      )
      .map {
        _.left.map { case CannotWriteToIndex => CannotSaveSettings }
      }
  }
}
object IndexJsonContentServiceBasedIndexMainSettingsManager {
  private [IndexJsonContentServiceBasedIndexMainSettingsManager] object Const {
    val id = "1"
    val settingsKey = "settings"
  }
}
