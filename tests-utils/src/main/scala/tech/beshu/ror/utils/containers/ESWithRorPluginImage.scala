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

import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder

object ESWithRorPluginImage extends EsImage[EsWithRorPluginContainer.Config] {

  private val rorConfigFileName = "readonlyrest.yml"
  private val log4j2FileName = "log4j2.properties"
  private val javaOptionsFileName = "jvm.options"
  private val keystoreFileName = "keystore.jks"
  private val truststoreFileName = "truststore.jks"
  private val configDir = "/config"

  override protected def copyNecessaryFiles(builder: DockerfileBuilder, config: EsWithRorPluginContainer.Config): DockerfileBuilder = {
    builder
      .copy(config.rorPluginFile.getAbsolutePath, "/tmp/")
      .from("build")
      .copy(s"$configDir/*", "/usr/share/elasticsearch/config/")
  }

  override protected def entry(config: EsWithRorPluginContainer.Config): ImageFromDockerfile = {
    new ImageFromDockerfile()
      .withFileFromFile(config.rorPluginFile.getAbsolutePath, config.rorPluginFile)
      .withFileFromFile(s"$configDir/$rorConfigFileName", config.rorConfigFile)
      .withFileFromFile(s"$configDir/$log4j2FileName", ContainerUtils.getResourceFile("/" + log4j2FileName))
      .withFileFromFile(s"$configDir/$keystoreFileName", ContainerUtils.getResourceFile("/" + keystoreFileName))
      .withFileFromFile(s"$configDir/$truststoreFileName", ContainerUtils.getResourceFile("/" + truststoreFileName))
      .withFileFromFile(s"$configDir/$javaOptionsFileName", ContainerUtils.getResourceFile("/" + javaOptionsFileName))
  }

  override protected def install(builder: DockerfileBuilder,
                                 config: EsWithRorPluginContainer.Config): DockerfileBuilder = {
    builder
      .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install file:///tmp/" + config.rorPluginFile.getName)
  }
}
