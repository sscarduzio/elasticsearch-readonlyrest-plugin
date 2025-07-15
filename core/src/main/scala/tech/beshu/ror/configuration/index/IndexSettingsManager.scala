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
import tech.beshu.ror.accesscontrol.domain.RorSettingsIndex
import tech.beshu.ror.configuration.RawRorSettingsYamlParser
import tech.beshu.ror.configuration.index.IndexSettingsManager.{LoadingIndexSettingsError, SavingIndexSettingsError}
import tech.beshu.ror.configuration.loader.RorSettingsLoader
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.SpecializedError

// todo: maybe we need settings manager to encapsulate file and index loading/saving logic?
// todo: it looks like this manager should extend RorConfigLoader
trait IndexSettingsManager[SETTINGS] {

  def settingsIndex: RorSettingsIndex

  def rorSettingsYamlParser: RawRorSettingsYamlParser

  def load(): Task[Either[RorSettingsLoader.Error[LoadingIndexSettingsError], SETTINGS]]

  def save(settings: SETTINGS): Task[Either[SavingIndexSettingsError, Unit]]

  // todo: is this ok?
  protected final def settingsLoaderError(error: LoadingIndexSettingsError): Task[Either[SpecializedError[LoadingIndexSettingsError], SETTINGS]] =
    Task.now(Left(SpecializedError[LoadingIndexSettingsError](error)))
}
object IndexSettingsManager {

  sealed trait LoadingIndexSettingsError
  object LoadingIndexSettingsError {
    case object IndexNotExist extends LoadingIndexSettingsError
    case object UnknownStructureOfIndexDocument extends LoadingIndexSettingsError
  }

  sealed trait SavingIndexSettingsError
  object SavingIndexSettingsError {
    case object CannotSaveSettings extends SavingIndexSettingsError
  }
}
