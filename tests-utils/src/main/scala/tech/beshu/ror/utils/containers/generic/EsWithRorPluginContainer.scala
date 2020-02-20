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
package tech.beshu.ror.utils.containers.generic

import java.io.File

import cats.data.NonEmptyList
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile

import scala.language.postfixOps

class EsWithRorPluginContainer private(name: String, esVersion: String, image: ImageFromDockerfile)
  extends EsContainer(name, esVersion, image)
    with StrictLogging {
  logger.info(s"[$name] Creating ES with ROR plugin installed container ...")

  override val sslEnabled: Boolean = true
}

object EsWithRorPluginContainer {

  final case class Config(nodeName: String,
                          nodes: NonEmptyList[String],
                          esVersion: String,
                          xPackSupport: Boolean,
                          rorPluginFile: File,
                          rorConfigFile: File,
                          configHotReloadingEnabled: Boolean,
                          internodeSslEnabled: Boolean) extends EsContainer.Config

  def create(config: EsWithRorPluginContainer.Config, initializer: ElasticsearchNodeDataInitializer) = {
    val rorContainer = new EsWithRorPluginContainer(
      config.nodeName,
      config.esVersion,
      ESWithRorPluginImage.create(config)
    )
    EsContainer.init(rorContainer, config, initializer)
  }
}

