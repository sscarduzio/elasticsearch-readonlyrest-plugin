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

import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.images.DockerImageCreator

import scala.language.postfixOps

class EsWithoutSecurityPluginContainer(name: String,
                                       esVersion: String,
                                       startedClusterDependencies: StartedClusterDependencies,
                                       esClusterSettings: EsClusterSettings,
                                       image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {
  logger.info(s"[$name] Creating ES without ROR plugin installed container ...")

  override val sslEnabled: Boolean = false
}

object EsWithoutSecurityPluginContainer extends StrictLogging {

  final case class Config(clusterName: String,
                          nodeName: String,
                          nodes: NonEmptyList[String],
                          envs: Map[String, String],
                          esVersion: String,
                          xPackSupport: Boolean,
                          useXpackSecurityInsteadOfRor: Boolean,
                          configHotReloadingEnabled: Boolean,
                          customRorIndexName: Option[String],
                          internodeSslEnabled: Boolean,
                          externalSslEnabled: Boolean,
                          forceNonOssImage: Boolean) extends EsContainer.Config

  def create(config: EsWithoutSecurityPluginContainer.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {
    val esContainer = new EsWithoutSecurityPluginContainer(
      config.nodeName,
      config.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      esImageFromDockerfile(config)
    )
    EsContainer.init(esContainer, config, initializer, logger)
  }

  private def esImageFromDockerfile(config: EsWithoutSecurityPluginContainer.Config) = {
    val esImageWithRor = new images.EsImage
    DockerImageCreator.create(
      esImageWithRor.create(images.EsImage.Config(
        config.esVersion,
        config.clusterName,
        config.nodeName, config.nodes
      ))
    )
  }
}

