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
package tech.beshu.ror.es.utils

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.common.settings.Settings
import tech.beshu.ror.tools.core.patches.PatchingVerifier
import tech.beshu.ror.tools.core.patches.PatchingVerifier.Error.{CannotVerifyIfPatched, EsNotPatched}
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

object EsPatchVerifier extends Logging {

  def verify(settings: Settings): Unit = doPrivileged {
    pathHomeFrom(settings).flatMap(PatchingVerifier.verify) match {
      case Right(_) =>
      case Left(e@EsNotPatched(_)) =>
        throw new IllegalStateException(e.message)
      case Left(e@CannotVerifyIfPatched(_)) =>
        logger.warn(e.message)
    }
  }

  private def pathHomeFrom(settings: Settings) =
    Option(settings.get("path.home")) match {
      case Some(esPath) => Right(esPath)
      case None => Left(CannotVerifyIfPatched("No 'path.home' setting."))
    }
}

