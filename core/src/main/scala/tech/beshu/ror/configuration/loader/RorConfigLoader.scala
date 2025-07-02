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
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RawRorConfigYamlParser.ParsingRorConfigError

trait RorConfigLoader[SPECIALIZED_ERROR] {

  def load(): Task[Either[RorConfigLoader.Error[SPECIALIZED_ERROR], RawRorConfig]]
}

object RorConfigLoader {

  sealed trait Error[+SPECIALIZED_ERROR]
  object Error {
    final case class ParsingError(error: ParsingRorConfigError) extends Error[Nothing]
    final case class SpecializedError[ERROR](error: ERROR) extends Error[ERROR]

    implicit def show[E: Show]: Show[Error[E]] = Show.show {
      case ParsingError(error) => Show[ParsingRorConfigError].show(error)
      case SpecializedError(error) => Show[E].show(error)
    }
  }

}