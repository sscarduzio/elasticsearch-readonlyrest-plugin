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

import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.EsContainerWithXpackSecurity.xpackAdminCredentials
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, EsImage, EsImageWithXpack}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.postfixOps

class EsContainerWithXpackSecurity private(name: String,
                                           esVersion: String,
                                           startedClusterDependencies: StartedClusterDependencies,
                                           esClusterSettings: EsClusterSettings,
                                           image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {

  logger.info(s"[$name] Creating ES with X-Pack plugin installed container ...")

  override val sslEnabled: Boolean = false

  override lazy val adminClient: RestClient =  basicAuthClient(xpackAdminCredentials._1, xpackAdminCredentials._2)
}


object EsContainerWithXpackSecurity extends StrictLogging {

  val xpackAdminCredentials: (String, String) = ("elastic", "elastic")

  def create(config: images.EsImage.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {

    val rorContainer = new EsContainerWithXpackSecurity(
      config.nodeName,
      config.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      esImageWithXpackFromDockerfile(config)
    )
    EsContainer.init(rorContainer, initializer, logger)
  }

    private def esImageWithXpackFromDockerfile(config: EsImage.Config) = {
      val esImageWithXpack = new EsImageWithXpack
      DockerImageCreator.create(
        esImageWithXpack.create(config)
      )
    }
}