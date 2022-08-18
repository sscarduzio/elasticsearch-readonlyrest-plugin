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
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Enabled
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, Elasticsearch, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.postfixOps

class EsContainerWithRorAndXpackSecurity private(esConfig: Elasticsearch.Config,
                                                 esVersion: String,
                                                 startedClusterDependencies: StartedClusterDependencies,
                                                 image: ImageFromDockerfile,
                                                 override val sslEnabled: Boolean)
  extends EsContainer(esVersion, esConfig, startedClusterDependencies, image)
    with StrictLogging {

  logger.info(s"[${esConfig.nodeName}] Creating ES with ROR and X-Pack plugin installed container ...")

  override lazy val adminClient: RestClient = {
    import EsContainerWithRorSecurity.rorAdminCredentials
    basicAuthClient(rorAdminCredentials._1, rorAdminCredentials._2)
  }
}

object EsContainerWithRorAndXpackSecurity extends StrictLogging {

  def create(esVersion: String,
             esConfig: Elasticsearch.Config,
             securityConfig: ReadonlyRestWithEnabledXpackSecurityPlugin.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val rorContainer = new EsContainerWithRorAndXpackSecurity(
      esConfig,
      esVersion,
      startedClusterDependencies,
      esImageWithRorAndXpackFromDockerfile(esVersion, esConfig, securityConfig),
      securityConfig.attributes.restSsl match {
        case Enabled.Yes(_) => true
        case Enabled.No => false
      }
    )
    EsContainer.init(rorContainer, initializer, logger)
  }

  private def esImageWithRorAndXpackFromDockerfile(esVersion: String,
                                                   esConfig: Elasticsearch.Config,
                                                   securityConfig: ReadonlyRestWithEnabledXpackSecurityPlugin.Config) = {
    DockerImageCreator.create(
      Elasticsearch.create(esVersion, esConfig)
        .install(new ReadonlyRestWithEnabledXpackSecurityPlugin(esVersion, securityConfig))
        .toDockerImageDescription
    )
  }
}