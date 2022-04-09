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

import better.files._
import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, ReadonlyRestPlugin}

import java.io.File
import scala.language.postfixOps

class EsWithSecurityPluginContainer private(name: String,
                                            esVersion: String,
                                            startedClusterDependencies: StartedClusterDependencies,
                                            esClusterSettings: EsClusterSettings,
                                            image: ImageFromDockerfile,
                                            externalSslEnabled: Boolean)
  extends EsContainer(name, esVersion, startedClusterDependencies, esClusterSettings, image)
    with StrictLogging {

  logger.info(s"[$name] Creating ES with ROR plugin installed container ...")

  override val sslEnabled: Boolean = externalSslEnabled
}

object EsWithSecurityPluginContainer extends StrictLogging {

  final case class Config(clusterName: String,
                          nodeName: String,
                          nodes: NonEmptyList[String],
                          envs: Map[String, String],
                          esVersion: String,
                          xPackSupport: Boolean,
                          useXpackSecurityInsteadOfRor: Boolean,
                          rorPluginFile: File,
                          rorConfigFile: File,
                          configHotReloadingEnabled: Boolean,
                          customRorIndexName: Option[String],
                          internodeSslEnabled: Boolean,
                          externalSslEnabled: Boolean,
                          forceNonOssImage: Boolean,
                          isFipsEnabled: Boolean)
    extends EsContainer.Config

  def create(config: EsWithSecurityPluginContainer.Config,
             initializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esClusterSettings: EsClusterSettings): EsContainer = {

    val rorContainer = new EsWithSecurityPluginContainer(
      config.nodeName,
      config.esVersion,
      startedClusterDependencies,
      esClusterSettings,
      esImageFromDockerfile(config),
      config.externalSslEnabled
    )
    EsContainer.init(rorContainer, config, initializer, logger)
  }

  private def esImageFromDockerfile(config: EsWithSecurityPluginContainer.Config) = {
    val esImageWithRor = new images.EsImage with ReadonlyRestPlugin
    DockerImageCreator.create(
      esImageWithRor.create(ReadonlyRestPlugin.Config(
        esConfig = images.EsImage.Config(
          config.esVersion,
          config.clusterName,
          config.nodeName, config.nodes
        ),
        rorConfig = config.rorConfigFile.toScala,
        rorPlugin = config.rorPluginFile.toScala,
        rorConfigHotReloading = config.configHotReloadingEnabled,
        rorCustomSettingsIndex = config.customRorIndexName,
        restSslEnabled = config.externalSslEnabled,
        internodeSslEnabled = config.internodeSslEnabled,
        isFipsEnabled = config.isFipsEnabled
      ))
    )
  }
}