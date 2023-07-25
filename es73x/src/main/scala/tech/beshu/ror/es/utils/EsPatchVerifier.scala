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
import tech.beshu.ror.tools.core.patches.EsPatch
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.util.Try

object EsPatchVerifier extends Logging {

  def verify(settings: Settings): Unit = {
    if (isXpackSecurityEnabled(settings)) {
      doVerify(settings)
    }
  }

  private def doVerify(settings: Settings): Unit = doPrivileged {
    val result = for {
      esHome <- pathHomeFrom(settings)
      esPatch <- createPatcher(esHome)
    } yield {
      if (!esPatch.isPatched) {
        throw new IllegalStateException("Elasticsearch is not patched. ReadonlyREST cannot be started. For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#3.-patch-elasticsearch")
      }
    }
    result match {
      case Right(_) =>
      case Left(errorCause) =>
        logger.warn(s"Cannot verify if the ES was patched. $errorCause")
    }
  }

  private def createPatcher(esHome: String) = {
    Try(EsPatch.create(os.Path(esHome)))
      .toEither
      .left.map(_.getMessage)
  }

  private def pathHomeFrom(settings: Settings) =
    Option(settings.get("path.home")) match {
      case Some(esPath) => Right(esPath)
      case None => Left("No 'path.home' setting.")
    }

  private def isXpackSecurityEnabled(settings: Settings): Boolean = {
    settings.getAsBoolean("xpack.security.enabled", false)
  }
}

