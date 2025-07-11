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
package tech.beshu.ror.configuration.loader

import cats.Show
import monix.eval.Task
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadFromIndexParameters
import tech.beshu.ror.configuration.loader.SettingsManager.{LoadingError, LoadingFromIndexError, SavingIndexSettingsError}

trait SettingsManager[SETTINGS] {

  def loadFromIndexWithFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                fallback: Task[Either[LoadingError, SETTINGS]]): Task[Either[LoadingError, SETTINGS]]

  def loadFromIndex(): Task[Either[LoadingFromIndexError, SETTINGS]]

  def saveToIndex(settings: SETTINGS): Task[Either[SavingIndexSettingsError, Unit]]
}
object SettingsManager {
  trait LoadingError

  sealed trait LoadingFromIndexError extends LoadingError
  object LoadingFromIndexError {
    final case class IndexParsingError(message: String) extends LoadingFromIndexError
    case object IndexUnknownStructure extends LoadingFromIndexError
    case object IndexNotExist extends LoadingFromIndexError

    implicit val show: Show[LoadingFromIndexError] = ???
  }

  sealed trait SavingIndexSettingsError
  object SavingIndexSettingsError {
    case object CannotSaveSettings extends SavingIndexSettingsError

    implicit val show: Show[SavingIndexSettingsError] = ???
  }
}