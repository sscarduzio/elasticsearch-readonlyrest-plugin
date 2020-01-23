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

class EsWithoutRorPluginContainer(name: String, esVersion: String, image: ImageFromDockerfile)
  extends RorContainer(name, esVersion, image)
  with StrictLogging {

  logger.info(s"[$name] Creating plain ES without ROR plugin container ...")

  override val sslEnabled: Boolean = false
}

object EsWithoutRorPluginContainer {

  final case class Config(nodeName: String,
                          nodes: NonEmptyList[String],
                          esVersion: String,
                          xPackSupport: Boolean) extends RorContainer.Config

  def create(config: EsWithoutRorPluginContainer.Config,
             initializer: ElasticsearchNodeDataInitializer) = {
    val esContainer = new EsWithoutRorPluginContainer(
      config.nodeName,
      config.esVersion,
      ESWithoutReadonlyRestImage.create(config)
    )
    RorContainer.init(esContainer, config, initializer)
  }
}

