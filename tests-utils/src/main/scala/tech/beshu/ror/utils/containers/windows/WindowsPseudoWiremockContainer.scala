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
package tech.beshu.ror.utils.containers.windows

import com.dimafeng.testcontainers.SingleContainer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.common.SingleRootFileSource
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.standalone.JsonFileMappingsSource
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.windows.WindowsPseudoWiremockContainer.WindowsPseudoGenericContainerWiremock

import java.io.File

class WindowsPseudoWiremockContainer(val port: Int, mappings: List[String])
  extends SingleContainer[GenericContainer[_]] {
  override val container: WindowsPseudoGenericContainerWiremock = new WindowsPseudoGenericContainerWiremock(port, mappings)
}

object WindowsPseudoWiremockContainer {
  class WindowsPseudoGenericContainerWiremock(port: Int, mappings: List[String])
    extends WindowsPseudoContainer[WindowsPseudoGenericContainerWiremock, WireMockServer] {

    override protected def name: String =
      "WireMockServerPseudoGenericContainer"

    override protected def prepare(): WireMockServer =
      WireMockServerCreator.create(port, mappings)

    override protected def awaitReady(): Unit =
      ()

    override protected def destroy(service: WireMockServer): Unit =
      service.stop()

    override protected def getPort(service: WireMockServer): Int =
      service.port()

  }
}

object WireMockServerCreator {
  def create(port: Int, mappings: List[String]): WireMockServer = synchronized {
    val mappingFiles: Seq[File] =
      mappings.map(ContainerUtils.getResourceFile)

    val config = WireMockConfiguration.wireMockConfig().port(port)

    mappingFiles.foreach { mappingFile =>
      val parentDir = mappingFile.getParentFile
      val fileSource = new SingleRootFileSource(parentDir.getAbsolutePath)
      config.mappingSource(new JsonFileMappingsSource(fileSource))
    }

    val wmServer = new WireMockServer(config)
    wmServer.start()
    wmServer
  }
}
