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
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.httpclient.RestClient

import java.util.function.Consumer

class EsContainerWithNoSecurity private(esConfig: Elasticsearch.Config,
                                        esVersion: String,
                                        startedClusterDependencies: StartedClusterDependencies,
                                        elasticsearch: Elasticsearch,
                                        initializer: ElasticsearchNodeDataInitializer,
                                        additionalLogConsumer: Option[Consumer[OutputFrame]] = scala.None,
                                        awaitingReadyStrategy: AwaitingReadyStrategy = AwaitingReadyStrategy.WaitForEsReadiness)
  extends EsContainer(esVersion, esConfig, startedClusterDependencies, elasticsearch, initializer, additionalLogConsumer, awaitingReadyStrategy) {

  logger.info(s"[${esConfig.nodeName}] Creating ES without any security installed container ...")

  override val sslEnabled: Boolean = false

  override lazy val adminClient: RestClient = noBasicAuthClient
}

object EsContainerWithNoSecurity extends StrictLogging {

  def create(esVersion: String,
             esConfig: Elasticsearch.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             additionalLogConsumer: Option[Consumer[OutputFrame]]): EsContainer = {
    new EsContainerWithNoSecurity(
      esConfig,
      esVersion,
      startedClusterDependencies,
      esImageFromDockerfile(esVersion, esConfig),
      initializer,
      additionalLogConsumer,
    )
  }

  private def esImageFromDockerfile(esVersion: String,
                                    esConfig: Elasticsearch.Config) = {
    Elasticsearch
      .create(
        esVersion,
        esConfig.copy(
          additionalElasticsearchYamlEntries = esConfig.additionalElasticsearchYamlEntries ++ Map("xpack.security.enabled" -> "false")
        )
      )
  }
}

