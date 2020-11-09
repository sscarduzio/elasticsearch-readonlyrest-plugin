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

import java.io.File

import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile

import scala.language.postfixOps

class EsWithRorPluginContainer private(name: String,
                                       esVersion: String,
                                       startedClusterDependencies: StartedClusterDependencies,
                                       esClusterSettings: EsClusterSettings,
                                       image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {

  logger.info(s"[$name] Creating ES with ROR plugin installed container ...")

  override val sslEnabled: Boolean = true
}

object EsWithRorPluginContainer extends StrictLogging {

  final case class Config(clusterName: String,
                          nodeName: String,
                          nodes: NonEmptyList[String],
                          envs: Map[String, String],
                          esVersion: String,
                          xPackSupport: Boolean,
                          rorPluginFile: File,
                          rorConfigFile: File,
                          configHotReloadingEnabled: Boolean,
                          customRorIndexName: Option[String],
                          internodeSslEnabled: Boolean,
                          externalSslEnabled: Boolean) extends EsContainer.Config

  def create(config: EsWithRorPluginContainer.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {
    val rorContainer = new EsWithRorPluginContainer(
      config.nodeName,
      config.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      ESWithRorPluginImage.create(config)
    )
    EsContainer.init(rorContainer, config, initializer, logger)
  }
}