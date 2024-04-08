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
package tech.beshu.ror.tools.core.patches

import tech.beshu.ror.tools.core.patches.PatchingVerifier.Error.{CannotVerifyIfPatched, EsNotPatched}
import tech.beshu.ror.tools.core.patches.base.EsPatch
import tech.beshu.ror.tools.core.patches.base.EsPatch.IsPatched.{No, Yes}

import scala.util.Try

object PatchingVerifier {

  def verify(esHome: String): Either[Error, Unit] = {
    for {
      esPatch <- createPatcher(esHome)
      result <- esPatch.isPatched match {
        case Yes => Right(())
        case No(cause) => Left(EsNotPatched(cause.message))
      }
    } yield result
  }

  private def createPatcher(esHome: String) = {
    Try(EsPatch.create(os.Path(esHome)))
      .toEither
      .left.map { e => CannotVerifyIfPatched(e.getMessage) }
  }

  sealed trait Error {
    def message: String
  }
  object Error {
    final case class EsNotPatched(override val message: String) extends Error
    final case class CannotVerifyIfPatched(errorCause: String) extends Error {
      override val message: String = s"Cannot verify if the ES was patched. $errorCause"
    }
  }

}

