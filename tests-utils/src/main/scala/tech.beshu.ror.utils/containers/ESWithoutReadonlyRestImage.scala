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
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import tech.beshu.ror.utils.containers.DockerfileBuilderOps._
import tech.beshu.ror.utils.containers.EsWithoutRorPluginContainer.Config
import tech.beshu.ror.utils.misc.Version

object ESWithoutReadonlyRestImage extends StrictLogging {

  def create(config: Config): ImageFromDockerfile = {
    import config._
    val baseDockerImage =
      if(shouldUseEsOssImage(config)) "docker.elastic.co/elasticsearch/elasticsearch-oss"
      else "docker.elastic.co/elasticsearch/elasticsearch"

    new ImageFromDockerfile()
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        builder
          .from(baseDockerImage + ":" + esVersion)
          .env("TEST_VAR", "dev")
          .run("/usr/share/elasticsearch/bin/elasticsearch-plugin remove x-pack --purge || rm -rf /usr/share/elasticsearch/plugins/*")
          .run("grep -v xpack /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(
            config.xPackSupport && Version.greaterOrEqualThan(esVersion, 6, 3, 0),
            "echo 'xpack.security.enabled: false' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .run("sed -i \"s|debug|info|g\" /usr/share/elasticsearch/config/log4j2.properties")
          .user("root")
          .run("chown elasticsearch:elasticsearch config/*")

        if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
          builder
            .run("(egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml || echo -n '') > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'discovery.seed_hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.initial_master_nodes: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.name: test-cluster' >> /usr/share/elasticsearch/config/elasticsearch.yml")
        } else {
          builder
            .run("(egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml || echo -n '') > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.master: true' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'discovery.zen.ping.unicast.hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.name: test-cluster' >> /usr/share/elasticsearch/config/elasticsearch.yml")
        }

        val javaOpts = List(
          "-Xms512m",
          "-Xmx512m",
          "-Djava.security.egd=file:/dev/./urandoms",
          "-Dcom.unboundid.ldap.sdk.debug.enabled=true"
        ).mkString(" ")

        builder.user("elasticsearch").env("ES_JAVA_OPTS", javaOpts)

        logger.info("Dockerfile\n" + builder.build)
      })
  }

  private def shouldUseEsOssImage(config: Config) = {
    !config.xPackSupport && Version.greaterOrEqualThan(config.esVersion, 6, 3, 0)
  }
}
