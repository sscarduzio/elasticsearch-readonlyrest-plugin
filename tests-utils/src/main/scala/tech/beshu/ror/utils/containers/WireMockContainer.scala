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

import better.files.{File => BetterFile}
import com.dimafeng.testcontainers.GenericContainer
import com.typesafe.scalalogging.LazyLogging
import org.apache.http.client.methods.HttpGet
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.WireMockContainer.{port, startupTimeout}
import tech.beshu.ror.utils.containers.images.DockerImageCreator
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils

import java.time.Duration
import scala.util.Try

class WireMockContainer private (mappingFiles: Seq[BetterFile])
    extends GenericContainer(
      dockerImage = WireMockContainer.image(mappingFiles),
      exposedPorts = Seq(port),
      waitStrategy = Some(new WireMockWaitStrategy().withStartupTimeout(startupTimeout))
    ) {

  container.setNetwork(TestNetwork.perJvm)
  container.withLogConsumer(frame => System.out.print(s"[WIREMOCK-ext-port:$wireMockPort] ${frame.getUtf8String}"))

  def wireMockHost: String = container.getHost

  def wireMockPort: Int = container.getMappedPort(port)

}

object WireMockContainer extends LazyLogging {

  val port = 8080
  val baseImage: String = DockerHubMirror.applyTo("rodolpheche/wiremock:2.5.1")
  private val startupTimeout = Duration.ofSeconds(240)

  def create(mappings: Seq[String]): WireMockContainer = {
    logger.info("Creating WireMock container ...")
    new WireMockContainer(mappings.map(mapping => BetterFile(ContainerUtils.getResourceFile(mapping).toPath)))
  }

  // Not deleteOnExit: the integration-tests build removes the labeled images after the test run.
  private def image(mappingFiles: Seq[BetterFile]): ImageFromDockerfile = {
    val image = new ImageFromDockerfile(s"ror-it-wiremock:${ScalaUtils.sha256(mappingFiles).take(20)}", false)
    mappingFiles.foreach(file => image.withFileFromPath(file.name, file.path))
    image.withDockerfileFromBuilder { builder =>
      builder
        .from(baseImage)
        .label(java.util.Map.of(DockerImageCreator.itImageLabel, "true"))
      mappingFiles.foreach(file => builder.copy(file.name, "/home/wiremock/mappings/"))
      // the image's own CMD plus -Xmx: its JDK 8 is not cgroup-aware and defaults to 1/4 of host RAM
      builder
        .cmd(
          "java",
          "-Xmx256m",
          "-cp",
          "/var/wiremock/lib/*:/var/wiremock/extensions/*",
          "com.github.tomakehurst.wiremock.standalone.WireMockServerRunner"
        )
        .build()
    }
  }

}

private class WireMockWaitStrategy extends WaitWithRetriesStrategy("WireMock") {

  override protected def isReady(): Boolean = Try {
    val client = new RestClient(false, waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(port))
    client.handle(new HttpGet(client.from("/__admin/")))(_.getStatusLine.getStatusCode == 200)
  }.getOrElse(false)

}
