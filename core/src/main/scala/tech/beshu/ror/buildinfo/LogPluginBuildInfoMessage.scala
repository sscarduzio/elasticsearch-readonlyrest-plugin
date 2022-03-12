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

import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.utils.AccessControllerHelper
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import scala.util.{Failure, Success}

object LogPluginBuildInfoMessage extends Logging {
  private val buildInfo = doPrivileged { BuildInfoReader.create() }

  def logBuildInfoMessage(): Unit = {
    buildInfo match {
      case Success(bf) => logger.info(createLogMessage(bf))
      case Failure(_) => logger.error("Cannot find build info file. No info about ReadonlyREST version.")
    }
  }

  def createLogMessage(buildInfo: BuildInfo): String =
    s"Starting ReadonlyREST plugin v${buildInfo.pluginVersion} on ES v${buildInfo.esVersion}"

  def apply(): Unit = {
    logBuildInfoMessage()
  }
}
