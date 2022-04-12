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
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, ReadonlyRestPlugin}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.postfixOps

class EsContainerWithRorSecurity private(name: String,
                                         esVersion: String,
                                         startedClusterDependencies: StartedClusterDependencies,
                                         esClusterSettings: EsClusterSettings,
                                         image: ImageFromDockerfile,
                                         override val sslEnabled: Boolean)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {

  logger.info(s"[$name] Creating ES with ROR plugin installed container ...")

  override lazy val adminClient: RestClient = {
    import EsContainerWithRorSecurity.rorAdminCredentials
    basicAuthClient(rorAdminCredentials._1, rorAdminCredentials._2)
  }
}

object EsContainerWithRorSecurity extends StrictLogging {

  val rorAdminCredentials: (String, String) = ("admin", "container")

  def create(config: ReadonlyRestPlugin.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {

    val rorContainer = new EsContainerWithRorSecurity(
      config.esConfig.nodeName,
      config.esConfig.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      esImageWithRorFromDockerfile(config),
      config.rorAttributes.restSslEnabled
    )
    EsContainer.init(rorContainer, initializer, logger)
  }

  private def esImageWithRorFromDockerfile(config: ReadonlyRestPlugin.Config) = {
    val esImageWithRor = new images.EsImage with ReadonlyRestPlugin
    DockerImageCreator.create(
      esImageWithRor.create(config)
    )
  }
}