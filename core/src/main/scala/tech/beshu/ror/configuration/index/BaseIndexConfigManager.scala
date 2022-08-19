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
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.SpecializedError

trait BaseIndexConfigManager[A] {

  def load(indexName: RorConfigurationIndex): Task[Either[ConfigLoaderError[IndexConfigError], A]]

  def save(config: A, rorConfigurationIndex: RorConfigurationIndex): Task[Either[SavingIndexConfigError, Unit]]

  protected final def configLoaderError(error: IndexConfigError): Task[Either[SpecializedError[IndexConfigError], A]] =
    Task.now(Left(SpecializedError[IndexConfigError](error)))
}
