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
package tech.beshu.ror.settings.strategy

import monix.eval.Task
import tech.beshu.ror.configuration.RawRorSettings
import tech.beshu.ror.configuration.manager.FileSettingsManager.LoadingFromFileError
import tech.beshu.ror.configuration.manager.InIndexSettingsManager.LoadingFromIndexError
import tech.beshu.ror.settings.source.{FileSettingsSource, IndexSettingsSource}
import tech.beshu.ror.settings.strategy.RorMainSettingsIndexWithFileFallbackLoadingStrategy.LoadingError

class RorMainSettingsIndexWithFileFallbackLoadingStrategy(indexSettingsSource: IndexSettingsSource[RawRorSettings],
                                                          fileSettingsSource: FileSettingsSource[RawRorSettings],
                                                          /* todo: retry strategy*/)
  extends SettingsLoadingStrategy[LoadingError, RawRorSettings] {

  override def load(): Task[Either[LoadingError, RawRorSettings]] = ???

}
object RorMainSettingsIndexWithFileFallbackLoadingStrategy {

  type LoadingError = Either[LoadingFromFileError, LoadingFromIndexError]

}