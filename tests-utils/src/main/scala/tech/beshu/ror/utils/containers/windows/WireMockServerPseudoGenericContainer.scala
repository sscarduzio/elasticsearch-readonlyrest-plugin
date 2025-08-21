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

import java.io.File

class WireMockServerPseudoContainer(mappings: List[String])
  extends SingleContainer[GenericContainer[_]] {

  override val container: WireMockServerPseudoGenericContainer = new WireMockServerPseudoGenericContainer(mappings)

  def port: Int = container.getWireMockPort
}

class WireMockServerPseudoGenericContainer(mappings: List[String])
  extends GenericContainer[WireMockServerPseudoGenericContainer]("noop:latest") {

  private var server: Option[WireMockServer] = None

  override def start(): Unit = {
    doStart()
  }

  override def doStart(): Unit = {
    server = Some(WireMockServerCreator.create(mappings))
  }

  override def stop(): Unit = {
    server.foreach(_.stop())
    super.stop()
  }

  def getWireMockPort: Int = server.map(_.port()).getOrElse(WireMockServerCreator.DefaultPort)

  override def getContainerId: String = "WireMockServerPseudoGenericContainer"

}

object WireMockServerCreator {
  val DefaultPort: Int = 8080

  private var server: Option[WireMockServer] = None

  def create(mappings: List[String]): WireMockServer = synchronized {
    server match {
      case Some(server) =>
        server
      case None =>
        val mappingFiles: Seq[File] =
          mappings.map(ContainerUtils.getResourceFile)

        val config = WireMockConfiguration.wireMockConfig().port(DefaultPort)

        mappingFiles.foreach { mappingFile =>
          val parentDir = mappingFile.getParentFile
          val fileSource = new SingleRootFileSource(parentDir.getAbsolutePath)
          config.mappingSource(new JsonFileMappingsSource(fileSource))
        }

        val wmServer = new WireMockServer(config)
        wmServer.start()
        server = Some(wmServer)
        wmServer
    }

  }
}
