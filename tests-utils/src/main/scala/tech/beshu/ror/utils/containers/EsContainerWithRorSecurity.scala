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
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, Elasticsearch, ReadonlyRestPlugin}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.postfixOps

class EsContainerWithRorSecurity private(esVersion: String,
                                         esConfig: Elasticsearch.Config,
                                         startedClusterDependencies: StartedClusterDependencies,
                                         image: ImageFromDockerfile,
                                         override val sslEnabled: Boolean)
  extends EsContainer(esVersion, esConfig, startedClusterDependencies, image)
    with StrictLogging {

  logger.info(s"[${esConfig.nodeName}] Creating ES with ROR plugin installed container ...")

  override lazy val adminClient: RestClient = {
    import EsContainerWithRorSecurity.rorAdminCredentials
    basicAuthClient(rorAdminCredentials._1, rorAdminCredentials._2)
  }
}

object EsContainerWithRorSecurity extends StrictLogging {

  val rorAdminCredentials: (String, String) = ("admin", "container")

  def create(esVersion: String,
             esConfig: Elasticsearch.Config,
             rorConfig: ReadonlyRestPlugin.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val rorContainer = new EsContainerWithRorSecurity(
      esVersion,
      esConfig,
      startedClusterDependencies,
      esImageWithRorFromDockerfile(esVersion, esConfig, rorConfig),
      rorConfig.attributes.restSslEnabled
    )
    EsContainer.init(rorContainer, initializer, logger)
  }

  private def esImageWithRorFromDockerfile(esVersion: String,
                                           esConfig: Elasticsearch.Config,
                                           rorConfig: ReadonlyRestPlugin.Config) = {
    DockerImageCreator.create(
      Elasticsearch.create(esVersion, esConfig)
        .install(new ReadonlyRestPlugin(esVersion, rorConfig))
        .toDockerImageDescription
    )
  }
}