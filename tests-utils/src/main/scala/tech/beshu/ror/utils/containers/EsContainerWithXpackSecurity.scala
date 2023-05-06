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
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, Elasticsearch, XpackSecurityPlugin}
import tech.beshu.ror.utils.httpclient.RestClient

class EsContainerWithXpackSecurity private(esVersion: String,
                                           esConfig: Elasticsearch.Config,
                                           startedClusterDependencies: StartedClusterDependencies,
                                           image: ImageFromDockerfile,
                                           override val sslEnabled: Boolean)
  extends EsContainer(esVersion, esConfig, startedClusterDependencies, image) {

  logger.info(s"[${esConfig.nodeName}] Creating ES with X-Pack plugin installed container ...")

  override lazy val adminClient: RestClient = basicAuthClient(xpackAdminCredentials._1, xpackAdminCredentials._2)
}

object EsContainerWithXpackSecurity extends StrictLogging {

  val xpackAdminCredentials: (String, String) = ("elastic", "elastic")

  def create(esVersion: String,
             esConfig: Elasticsearch.Config,
             xpackSecurityConfig: XpackSecurityPlugin.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies): EsContainer = {

    val rorContainer = new EsContainerWithXpackSecurity(
      esVersion,
      esConfig,
      startedClusterDependencies,
      esImageWithXpackFromDockerfile(esVersion, esConfig, xpackSecurityConfig),
      xpackSecurityConfig.attributes.restSslEnabled
    )
    EsContainer.init(rorContainer, initializer, logger)
  }

  private def esImageWithXpackFromDockerfile(esVersion: String,
                                             esConfig: Elasticsearch.Config,
                                             xpackSecurityConfig: XpackSecurityPlugin.Config) = {
    DockerImageCreator.create(
      Elasticsearch
        .create(esVersion, esConfig)
        .install(new XpackSecurityPlugin(esVersion, xpackSecurityConfig))
        .toDockerImageDescription
    )
  }
}