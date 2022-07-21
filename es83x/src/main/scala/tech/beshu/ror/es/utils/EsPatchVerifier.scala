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

import scala.util.Try

object EsPatchVerifier extends Logging {

  def verify(settings: Settings): Unit = {
    val result = for {
      esHome <- Option(pathHomeFrom(settings))
      esPatch <- Try(EsPatch.create(os.Path(esHome))).toOption
    } yield {
      if (!esPatch.isPatched) {
        throw new IllegalStateException("Elasticsearch is not patched. ReadonlyREST cannot be started. For patching instructions see our docs: https://docs.readonlyrest.com/elasticsearch#3.-patch-es")
      }
    }
    result match {
      case Some(_) =>
      case None =>
        logger.warn(s"Cannot verify if the ES was patched. Path.home=[${pathHomeFrom(settings)}]")
    }
  }

  private def pathHomeFrom(settings: Settings) = settings.get("path.home")
}

