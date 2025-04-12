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
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor
import tech.beshu.ror.tools.core.patches.base.EsPatchExecutor.EsPatchStatus
import tech.beshu.ror.tools.core.utils.EsDirectory
import tech.beshu.ror.tools.core.utils.InOut.ConsoleInOut
import tech.beshu.ror.tools.core.utils.RorToolsError.{EsNotPatchedError, EsPatchedWithDifferentVersionError}

import scala.util.Try

object PatchingVerifier {

  def verify(esHome: String): Either[Error, Unit] = {
    for {
      esPatchExecutor <- createEsPatchExecutor(esHome)
      result <- esPatchExecutor.isPatched match
        case EsPatchStatus.PatchedWithCurrentRorVersion(_) =>
          Right(())
        case EsPatchStatus.PatchedWithOtherRorVersion(expectedRorVersion, patchedByRorVersion) =>
          Left(EsNotPatched(EsPatchedWithDifferentVersionError(expectedRorVersion, patchedByRorVersion).message))
        case EsPatchStatus.NotPatched =>
          Left(EsNotPatched(EsNotPatchedError.message))
    } yield result
  }

  private def createEsPatchExecutor(esHome: String) = {
    Try(EsPatchExecutor.create(EsDirectory.from(os.Path(esHome)))(ConsoleInOut))
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

