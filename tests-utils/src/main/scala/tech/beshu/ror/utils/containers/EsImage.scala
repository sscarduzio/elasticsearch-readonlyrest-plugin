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
import tech.beshu.ror.utils.containers.EsContainer.Config
import tech.beshu.ror.utils.misc.Version

import scala.collection.JavaConverters._
import scala.language.postfixOps

trait EsImage[CONFIG <: EsContainer.Config] extends StrictLogging {

  protected def copyNecessaryFiles(builder: DockerfileBuilder, config: CONFIG): DockerfileBuilder = builder

  protected def entry(config: CONFIG): ImageFromDockerfile = new ImageFromDockerfile()

  protected def install(builder: DockerfileBuilder, config: CONFIG): DockerfileBuilder = builder

  def create(config: CONFIG): ImageFromDockerfile = {
    import config._
    val baseDockerImage =
      if (shouldUseEsNonOssImage(config)) "docker.elastic.co/elasticsearch/elasticsearch"
      else "docker.elastic.co/elasticsearch/elasticsearch-oss"

    entry(config)
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        builder
          .from(baseDockerImage + ":" + esVersion)

        copyNecessaryFiles(builder, config)

        RunCommandCombiner.empty
          .runWhen(!useXpackSecurityInsteadOfRor, "/usr/share/elasticsearch/bin/elasticsearch-plugin remove x-pack --purge || rm -rf /usr/share/elasticsearch/plugins/*")
          .run("grep -v xpack /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(shouldDisableXpack(config),
            command = "echo 'xpack.security.enabled: false' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .runWhen(externalSslEnabled, "echo 'http.type: ssl_netty4' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(internodeSslEnabled, "echo 'transport.type: ror_ssl_internode' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(!configHotReloadingEnabled, "echo 'readonlyrest.force_load_from_file: true' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(customRorIndexName.isDefined, s"echo 'readonlyrest.settings_index: ${customRorIndexName.get}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(useXpackSecurityInsteadOfRor, "printf 'xpack.security.enabled: true\\n" +
            "xpack.security.transport.ssl.enabled: true\\n" +
            "xpack.security.transport.ssl.verification_mode: none\\n" +
            "xpack.security.transport.ssl.client_authentication: none\\n" +
            "xpack.security.transport.ssl.keystore.path: elastic-certificates.p12\\n" +
            "xpack.security.transport.ssl.truststore.path: elastic-certificates.p12'" +
            ">> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(useXpackSecurityInsteadOfRor, "printf '\\n\\ny\\n' | /usr/share/elasticsearch/bin/elasticsearch-keystore create && " +
            "printf 'readonlyrest\\n' | /usr/share/elasticsearch/bin/elasticsearch-keystore add xpack.security.transport.ssl.keystore.secure_password && " +
            "printf 'readonlyrest\\n' | /usr/share/elasticsearch/bin/elasticsearch-keystore add xpack.security.transport.ssl.truststore.secure_password" )
          .run("sed -i \"s|debug|info|g\" /usr/share/elasticsearch/config/log4j2.properties")
          .runWhen(Version.greaterOrEqualThan(esVersion, 6, 6, 0),
            command = "echo '/usr/local/bin/docker-entrypoint.sh &' > /usr/share/elasticsearch/xpack-setup-entry.sh",
            orElse = "echo '/usr/share/elasticsearch/bin/es-docker &' > /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo 'sleep 45' >> /usr/share/elasticsearch/xpack-setup-entry.sh") // Time needed to bootstrap cluster as elasticsearch-setup-passwords have to be run after cluster is ready
          .run("echo 'export ES_JAVA_OPTS=\"-Xms1g -Xmx1g -Djava.security.egd=file:/dev/./urandoms\"' >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo 'echo \"Setting up default passwords\"' >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo 'printf \"y\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\nelastic\\n\" | elasticsearch-setup-passwords interactive' >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo \"curl -X POST -u elastic:elastic \"http://localhost:9200/_security/user/admin?pretty\" -H 'Content-Type: application/json' -d'{\\\"password\\\" : \\\"container\\\",\\\"roles\\\" : [ \\\"superuser\\\"]}'\n\" >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo \"curl -X PUT \"http://localhost:9200/_xpack/security/user/elastic/_password?pretty\" -H 'Content-Type: application/json' -d'\n{\n  \"password\": \"elastic\"\n}\n'\n\" >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("echo 'wait' >> /usr/share/elasticsearch/xpack-setup-entry.sh")
          .run("chmod +x /usr/share/elasticsearch/xpack-setup-entry.sh")
          .applyTo(builder)
          .user("root")

        RunCommandCombiner.empty
          .run("chown elasticsearch:elasticsearch config/*")
          .run("(egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml || echo -n '') > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
          .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .run("echo 'path.repo: /tmp' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .run("echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .run(s"echo 'cluster.name: $clusterName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .run(s"echo 'cluster.routing.allocation.disk.threshold_enabled: false' >> /usr/share/elasticsearch/config/elasticsearch.yml")
          .runWhen(config.xPackSupport && Version.greaterOrEqualThan(esVersion, 7, 6, 0),
            command = s"echo 'indices.lifecycle.history_index_enabled: false' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .runWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
            command = s"echo 'discovery.seed_hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml",
            orElse = s"echo 'discovery.zen.ping.unicast.hosts: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .runWhen(Version.greaterOrEqualThan(esVersion, 7, 0, 0),
            command = s"echo 'cluster.initial_master_nodes: ${nodes.toList.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml",
            orElse = s"echo 'node.master: true' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .runWhen(Version.greaterOrEqualThan(esVersion, 7, 14, 0),
            command = s"echo 'ingest.geoip.downloader.enabled: false' >> /usr/share/elasticsearch/config/elasticsearch.yml"
          )
          .applyTo(builder)

        val javaOpts = {
          xmJavaOptions() ++
            urandomJavaOption() ++
            unboundidDebug(false) ++
            rorHotReloading(configHotReloadingEnabled) ++
            xDebugJavaOptions(esVersion)
        }.mkString(" ")

        if (useXpackSecurityInsteadOfRor)
          builder.entryPoint("/usr/share/elasticsearch/xpack-setup-entry.sh")

        builder
          .user("elasticsearch")
          .env(config.envs + ("ES_JAVA_OPTS" -> javaOpts) asJava)

        install(builder, config)

        logger.info("Dockerfile\n" + builder.build)
      })
  }

  private def xmJavaOptions() = List("-Xms1g", "-Xmx1g")

  private def urandomJavaOption() = List("-Djava.security.egd=file:/dev/./urandoms")

  private def unboundidDebug(enabled: Boolean) = List(s"-Dcom.unboundid.ldap.sdk.debug.enabled=${if(enabled) true else false}")

  private def xDebugJavaOptions(esVersion: String) = {
    val address = if (Version.greaterOrEqualThan(esVersion, 6, 3, 0)) "*:8000" else "8000"
    List("-Xdebug", s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$address")
  }

  private def rorHotReloading(enabled: Boolean) = List(if(!enabled) "-Dcom.readonlyrest.settings.refresh.interval=0" else "")

  private def shouldUseEsNonOssImage(config: Config) = {
    config.xPackSupport ||
      config.useXpackSecurityInsteadOfRor ||
      config.forceNonOssImage ||
      Version.lowerThan(config.esVersion, 6, 3, 0) ||
      Version.greaterOrEqualThan(config.esVersion, 7, 11, 0)
  }

  private def shouldDisableXpack(config: Config) = {
    shouldUseEsNonOssImage(config) &&
      Version.greaterOrEqualThan(config.esVersion, 6, 3, 0) &&
      !config.useXpackSecurityInsteadOfRor
  }
}