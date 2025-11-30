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
package tech.beshu.ror.buildinfo

import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.util.{Failure, Success}

object LogPluginBuildInfoMessage extends RequestIdAwareLogging {
  private val buildInfo = doPrivileged { BuildInfoReader.create() }

  def apply(): Unit = {
    logBuildInfoMessage()
  }

  private def logBuildInfoMessage(): Unit = {
    buildInfo match {
      case Success(bf) => noRequestIdLogger.info(createLogMessage(bf))
      case Failure(_) => noRequestIdLogger.error("Cannot find build info file. No info about ReadonlyREST version.")
    }
  }

  private def createLogMessage(buildInfo: BuildInfo): String =
    s"Starting ReadonlyREST plugin v${buildInfo.pluginVersion.show} on Elasticsearch v${buildInfo.esVersion.show}"

}
