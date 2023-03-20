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
package tech.beshu.ror.utils.containers

import better.files.File
import tech.beshu.ror.utils.containers.ContainerOps._

object RorConfigAdjuster {

  private val hostPlaceholder = "HOST"
  private val portPlaceholder = "PORT"

  final case class Replacement(host: String, port: Int)

  def adjustUsingDependencies(config: String,
                              startedDependencies: StartedClusterDependencies): String = {
    startedDependencies.values
      .foldLeft(config)(replacePlaceholder)
  }

  def adjustUsingDependencies(source: File,
                              startedDependencies: StartedClusterDependencies): File = {
    val configWithResolvedDependencies = startedDependencies.values
      .foldLeft(source.contentAsString)(replacePlaceholder)

    createTempFile.overwrite(configWithResolvedDependencies)
  }

  private def replacePlaceholder(fileContent: String,
                                 dependency: StartedDependency): String = {
    val replacement = resolveReplacementForGivenMode(dependency)
    fileContent
      .replaceAll(s"\\{${dependency.name}_$hostPlaceholder\\}", replacement.host)
      .replaceAll(s"\\{${dependency.name}_$portPlaceholder\\}", replacement.port.toString)
  }

  private def resolveReplacementForGivenMode(dependency: StartedDependency): Replacement = {
    Replacement(
      host = dependency.container.ipAddressFromFirstNetwork.getOrElse(throw new IllegalStateException("Could not extract ip address inside docker network")),
      port = dependency.originalPort
    )
  }

  private def createTempFile = File.newTemporaryFile("tmp", ".tmp").deleteOnExit()
}
