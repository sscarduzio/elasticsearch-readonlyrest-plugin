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
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError

trait ConfigLoader[SPECIALIZED_ERROR] {

  def load(): Task[Either[ConfigLoaderError[SPECIALIZED_ERROR], RawRorConfig]]

}

object ConfigLoader {

  sealed trait ConfigLoaderError[+SPECIALIZED_ERROR]
  object ConfigLoaderError {
    final case class ParsingError(error: ParsingRorConfigError) extends ConfigLoaderError[Nothing]
    final case class SpecializedError[ERROR](error: ERROR) extends ConfigLoaderError[ERROR]

    implicit def show[E: Show]: Show[ConfigLoaderError[E]] = Show.show {
      case ParsingError(error) => Show[RawRorConfig.ParsingRorConfigError].show(error)
      case SpecializedError(error) => Show[E].show(error)
    }
  }

}