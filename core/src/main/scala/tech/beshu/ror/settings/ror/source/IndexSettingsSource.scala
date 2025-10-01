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
package tech.beshu.ror.settings.ror.source

import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.IndexDocumentManager
import tech.beshu.ror.es.IndexDocumentManager.CannotWriteToIndex
import tech.beshu.ror.settings.ror.source.IndexSettingsSource.LoadingError.{DocumentNotFound, IndexNotFound}
import tech.beshu.ror.settings.ror.source.IndexSettingsSource.SavingError.CannotSaveSettings
import tech.beshu.ror.settings.ror.source.IndexSettingsSource.{IndexSettingsLoadingError, IndexSettingsSavingError, LoadingError, SavingError}
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SavingSettingsError

class IndexSettingsSource[SETTINGS: Encoder : Decoder](indexDocumentManager: IndexDocumentManager,
                                                       val settingsIndex: IndexName.Full,
                                                       documentId: String)
  extends ReadWriteSettingsSource[SETTINGS, LoadingError, SavingError] {

  override def load(): Task[Either[IndexSettingsLoadingError, SETTINGS]] = {
    indexDocumentManager
      .documentAsJson(settingsIndex, documentId)
      .map {
        case Right(document) =>
          document.as[SETTINGS]
            .left.map { decodingFailure =>
              LoadingSettingsError.SettingsMalformed(decodingFailure.message)
            }
        case Left(IndexDocumentManager.IndexNotFound) =>
          settingsLoaderError(IndexNotFound)
        case Left(IndexDocumentManager.DocumentNotFound) =>
          settingsLoaderError(DocumentNotFound)
        case Left(IndexDocumentManager.DocumentUnreachable) =>
          settingsLoaderError(IndexNotFound) // todo: throw ex?
      }
  }

  override def save(settings: SETTINGS): Task[Either[IndexSettingsSavingError, Unit]] = {
    indexDocumentManager
      .saveDocumentJson(settingsIndex, documentId, settings.asJson)
      .map {
        _.left.map { case CannotWriteToIndex => SavingSettingsError.SourceSpecificError(CannotSaveSettings) }
      }
  }

  private def settingsLoaderError(error: LoadingError) =
    Left(LoadingSettingsError.SourceSpecificError(error))

}
object IndexSettingsSource {

  type IndexSettingsLoadingError = LoadingSettingsError[LoadingError]

  sealed trait LoadingError
  object LoadingError {
    case object IndexNotFound extends LoadingError
    case object DocumentNotFound extends LoadingError
  }

  type IndexSettingsSavingError = SavingSettingsError[SavingError]

  sealed trait SavingError
  object SavingError {
    case object CannotSaveSettings extends SavingError
  }
}
