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
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, Elasticsearch}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.language.postfixOps

class EsContainerWithNoSecurity(name: String,
                                esVersion: String,
                                startedClusterDependencies: StartedClusterDependencies,
                                esClusterSettings: EsClusterSettings,
                                image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {

  logger.info(s"[$name] Creating ES without any security installed container ...")

  override val sslEnabled: Boolean = false

  override lazy val adminClient: RestClient = noBasicAuthClient
}

object EsContainerWithNoSecurity extends StrictLogging {

  def create(esVersion: String,
             esConfig: Elasticsearch.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {
    val esContainer = new EsContainerWithNoSecurity(
      esConfig.nodeName,
      esVersion,
      startedClusterDependencies,
      esClusterSettings,
      esImageFromDockerfile(esVersion, esConfig)
    )
    EsContainer.init(esContainer, initializer, logger)
  }

  private def esImageFromDockerfile(esVersion: String,
                                    esConfig: Elasticsearch.Config) = {
    DockerImageCreator.create(
      Elasticsearch
        .create(
          esVersion,
          esConfig.copy(
            additionalElasticsearchYamlEntries = esConfig.additionalElasticsearchYamlEntries ++ Map("xpack.security.enabled" -> "false")
          )
        )
        .toDockerImageDescription
    )
  }
}

