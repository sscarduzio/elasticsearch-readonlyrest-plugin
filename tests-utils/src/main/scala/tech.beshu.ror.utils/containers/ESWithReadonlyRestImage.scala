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
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import tech.beshu.ror.utils.misc.Version

object ESWithReadonlyRestImage extends StrictLogging {

  private val rorConfigFileName = "readonlyrest.yml"
  private val log4j2FileName = "log4j2.properties"
  private val javaOptionsFileName = "jvm.options"
  private val keystoreFileName = "keystore.jks"

  def create(nodeName: String,
             nodes: NonEmptyList[String],
             esVersion: String,
             rorPluginFile: File,
             rorConfigFile: File): ImageFromDockerfile = {
    val baseDockerImage =
      if (Version.greaterOrEqualThan(esVersion, 6, 3, 0)) "docker.elastic.co/elasticsearch/elasticsearch-oss"
      else "docker.elastic.co/elasticsearch/elasticsearch"

    new ImageFromDockerfile()
      .withFileFromFile(rorPluginFile.getAbsolutePath, rorPluginFile)
      .withFileFromFile(rorConfigFileName, rorConfigFile)
      .withFileFromFile(log4j2FileName, ContainerUtils.getResourceFile("/" + log4j2FileName))
      .withFileFromFile(keystoreFileName, ContainerUtils.getResourceFile("/" + keystoreFileName))
      .withFileFromFile(javaOptionsFileName, ContainerUtils.getResourceFile("/" + javaOptionsFileName))
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        builder
          .from(baseDockerImage + ":" + esVersion)
          .env("TEST_VAR", "dev")
          .copy(rorPluginFile.getAbsolutePath, "/tmp/")
          .copy(log4j2FileName, "/usr/share/elasticsearch/config/")
          .copy(keystoreFileName, "/usr/share/elasticsearch/config/")
          .copy(javaOptionsFileName, "/usr/share/elasticsearch/config/")
          .copy(rorConfigFileName, "/usr/share/elasticsearch/config/readonlyrest.yml")
          .run("/usr/share/elasticsearch/bin/elasticsearch-plugin remove x-pack --purge || rm -rf /usr/share/elasticsearch/plugins/*")
          .run("grep -v xpack /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
          .run("echo 'http.type: ssl_netty4' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .run("sed -i \"s|debug|info|g\" /usr/share/elasticsearch/config/log4j2.properties")
          .user("root")
          .run("chown elasticsearch:elasticsearch config/*")

        if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
          builder
            .run("egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'discovery.seed_hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.initial_master_nodes: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.name: test-cluster' >> /usr/share/elasticsearch/config/elasticsearch.yml")
        } else {
          builder
            .run("egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.master: true' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'discovery.zen.ping.unicast.hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.name: test-cluster' >> /usr/share/elasticsearch/config/elasticsearch.yml")
        }

        builder.user("elasticsearch")
          .env("ES_JAVA_OPTS", "-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandoms -Dcom.unboundid.ldap.sdk.debug.enabled=true")
          .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install file:///tmp/" + rorPluginFile.getName)

        logger.info("Dockerfile\n" + builder.build)
      })
  }
}
