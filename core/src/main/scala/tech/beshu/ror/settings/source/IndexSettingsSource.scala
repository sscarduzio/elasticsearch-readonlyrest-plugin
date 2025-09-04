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
package tech.beshu.ror.settings.source

import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RorSettingsIndex
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.es.IndexJsonContentService.{CannotReachContentSource, CannotWriteToIndex, ContentNotFound}
import tech.beshu.ror.settings.source.IndexSettingsSource.LoadingError.IndexNotFound
import tech.beshu.ror.settings.source.IndexSettingsSource.SavingError.CannotSaveSettings
import tech.beshu.ror.settings.source.IndexSettingsSource.{LoadingError, SavingError}
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.source.ReadWriteSettingsSource.SavingSettingsError

class IndexSettingsSource[SETTINGS : Encoder : Decoder](indexJsonContentService: IndexJsonContentService,
                                                        settingsIndex: RorSettingsIndex,
                                                        documentId: String)
  extends ReadWriteSettingsSource[SETTINGS, LoadingError, SavingError] {

  override def load(): Task[Either[LoadingSettingsError[LoadingError], SETTINGS]] = {
    indexJsonContentService
      .sourceOfAsString(settingsIndex.index, documentId)
      .map {
        case Right(document) =>
          document
            .as[SETTINGS]
            .left.flatMap { _ =>
              settingsLoaderError(IndexNotFound) // todo: wrong type
            }
        case Left(CannotReachContentSource) =>
          settingsLoaderError(IndexNotFound)
        case Left(ContentNotFound) =>
          settingsLoaderError(IndexNotFound)
      }
  }

  override def save(settings: SETTINGS): Task[Either[SavingSettingsError[SavingError], Unit]] = {
    indexJsonContentService
      .saveContentJson(settingsIndex.index, documentId, settings.asJson)
      .map {
        _.left.map { case CannotWriteToIndex => SavingSettingsError.SourceSpecificError(CannotSaveSettings) }
      }
  }

  private def settingsLoaderError(error: LoadingError) =
    Left(LoadingSettingsError.SourceSpecificError(error))

}
object IndexSettingsSource {
  sealed trait LoadingError
  object LoadingError {
    case object IndexNotFound extends LoadingError
  }

  sealed trait SavingError
  object SavingError {
    case object CannotSaveSettings extends SavingError
  }
}
