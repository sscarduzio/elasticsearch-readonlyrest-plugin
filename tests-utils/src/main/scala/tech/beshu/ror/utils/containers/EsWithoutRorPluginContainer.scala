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

import scala.language.postfixOps

class EsWithoutRorPluginContainer(name: String,
                                  esVersion: String,
                                  startedClusterDependencies: StartedClusterDependencies,
                                  esClusterSettings: EsClusterSettings,
                                  image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {
  logger.info(s"[$name] Creating ES without ROR plugin installed container ...")

  override val sslEnabled: Boolean = false
}

object EsWithoutRorPluginContainer extends StrictLogging {

  final case class Config(clusterName: String,
                          nodeName: String,
                          nodes: NonEmptyList[String],
                          envs: Map[String, String],
                          esVersion: String,
                          xPackSupport: Boolean,
                          enableFullXPack: Boolean,
                          configHotReloadingEnabled: Boolean,
                          customRorIndexName: Option[String],
                          internodeSslEnabled: Boolean,
                          externalSslEnabled: Boolean,
                          forceNonOssImage: Boolean) extends EsContainer.Config

  def create(config: EsWithoutRorPluginContainer.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {
    val esContainer = new EsWithoutRorPluginContainer(
      config.nodeName,
      config.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      ESWithoutRorPluginImage.create(config)
    )
    EsContainer.init(esContainer, config, initializer, logger)
  }
}

