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
import org.testcontainers.containers.output.OutputFrame
import os.Path
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.containers.images.{Elasticsearch, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.httpclient.RestClient

import java.util.function.Consumer

class EsContainerWithRorAndXpackSecurity private(esConfig: Elasticsearch.Config,
                                                 esVersion: String,
                                                 startedClusterDependencies: StartedClusterDependencies,
                                                 elasticsearch: Elasticsearch,
                                                 override val sslEnabled: Boolean,
                                                 initializer: ElasticsearchNodeDataInitializer,
                                                 additionalLogConsumer: Option[Consumer[OutputFrame]] = scala.None,
                                                 awaitingReadyStrategy: AwaitingReadyStrategy = AwaitingReadyStrategy.WaitForEsReadiness)
  extends EsContainer(esVersion, esConfig, startedClusterDependencies, elasticsearch, initializer, additionalLogConsumer, awaitingReadyStrategy) {

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
             startedClusterDependencies: StartedClusterDependencies,
             additionalLogConsumer: Option[Consumer[OutputFrame]]): EsContainer = {
    create(
      esVersion = esVersion,
      esConfig = esConfig,
      securityConfig = securityConfig,
      initializer = initializer,
      startedClusterDependencies = startedClusterDependencies,
      customEntrypoint = None,
      performPatching = true,
      awaitingReadyStrategy = AwaitingReadyStrategy.WaitForEsReadiness,
      additionalLogConsumer = additionalLogConsumer,
    )
  }

  def createWithPatchingDisabled(esVersion: String,
                                 esConfig: Elasticsearch.Config,
                                 securityConfig: ReadonlyRestWithEnabledXpackSecurityPlugin.Config,
                                 initializer: ElasticsearchNodeDataInitializer,
                                 startedClusterDependencies: StartedClusterDependencies,
                                 customEntrypoint: Option[Path],
                                 awaitingReadyStrategy: AwaitingReadyStrategy,
                                 additionalLogConsumer: Option[Consumer[OutputFrame]]): EsContainer = {
    create(
      esVersion = esVersion,
      esConfig = esConfig,
      securityConfig = securityConfig,
      initializer = initializer,
      startedClusterDependencies = startedClusterDependencies,
      customEntrypoint = customEntrypoint,
      performPatching = false,
      awaitingReadyStrategy = awaitingReadyStrategy,
      additionalLogConsumer = additionalLogConsumer,
    )
  }

  private def create(esVersion: String,
                     esConfig: Elasticsearch.Config,
                     securityConfig: ReadonlyRestWithEnabledXpackSecurityPlugin.Config,
                     initializer: ElasticsearchNodeDataInitializer,
                     startedClusterDependencies: StartedClusterDependencies,
                     customEntrypoint: Option[Path],
                     performPatching: Boolean,
                     awaitingReadyStrategy: AwaitingReadyStrategy,
                     additionalLogConsumer: Option[Consumer[OutputFrame]]): EsContainer = {
    new EsContainerWithRorAndXpackSecurity(
      esConfig,
      esVersion,
      startedClusterDependencies,
      esImageWithRorAndXpackFromDockerfile(esVersion, esConfig, securityConfig, customEntrypoint, performPatching),
      securityConfig.attributes.restSsl match {
        case Enabled.Yes(_) => true
        case Enabled.No => false
      },
      initializer,
      additionalLogConsumer,
      awaitingReadyStrategy,
    )
  }

  private def esImageWithRorAndXpackFromDockerfile(esVersion: String,
                                                   esConfig: Elasticsearch.Config,
                                                   securityConfig: ReadonlyRestWithEnabledXpackSecurityPlugin.Config,
                                                   customEntrypoint: Option[Path],
                                                   performPatching: Boolean) = {
    Elasticsearch.create(esVersion, esConfig)
      .install(new ReadonlyRestWithEnabledXpackSecurityPlugin(esVersion, securityConfig, performPatching))
      .when(customEntrypoint, _.setEntrypoint(_))
  }
}