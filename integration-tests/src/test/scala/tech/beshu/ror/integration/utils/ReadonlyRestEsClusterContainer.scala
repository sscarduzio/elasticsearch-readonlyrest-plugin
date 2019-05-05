package tech.beshu.ror.integration.utils

import java.io.File
import java.time.Duration
import java.util
import java.util.function.BiPredicate
import java.util.{List, Map, Optional}

import com.dimafeng.testcontainers.{LazyContainer, SingleContainer}
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.containers.{GenericContainer => OTCGenericContainer}
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import tech.beshu.ror.integration.utils.ReadonlyRestEsContainer.OTCContainer
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.misc.{Tuple, Version}
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.{AbstractWaitStrategy, Wait, WaitStrategy, WaitStrategyTarget}
import tech.beshu.ror.utils.httpclient.RestClient
import ReadonlyRestEsClusterContainer.adminCredentials
import net.jodah.failsafe.{Failsafe, RetryPolicy}
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import tech.beshu.ror.integration.utils.JavaScalaUtils._

import scala.language.existentials

class ReadonlyRestEsClusterContainer private(containers: Seq[LazyContainer[_]])
  extends MultipleContainers(containers) {
}

object ReadonlyRestEsClusterContainer {

  val adminCredentials = ("admin", "container")

  def create(rorConfigFileName: String): ReadonlyRestEsClusterContainer =
    create(ContainerUtils.getResourceFile(rorConfigFileName))

  def create(rorConfigFile: File): ReadonlyRestEsClusterContainer = {
    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val ror1 = new ReadonlyRestEsContainer(project.getESVersion, rorPluginFile, rorConfigFile)
    val ror2 = new ReadonlyRestEsContainer(project.getESVersion, rorPluginFile, rorConfigFile)
    new ReadonlyRestEsClusterContainer(Seq(ror1, ror2))
  }
}

private class ReadonlyRestEsContainer(esVersion: String,
                                      rorPluginFile: File,
                                      rorConfigFile: File)
  extends SingleContainer[OTCContainer]
    with StrictLogging {

  logger.info("Creating ROR container ...")

  override implicit val container: OTCContainer = new OTCGenericContainer(createImage(rorPluginFile))
  container
    .withLogConsumer(new Slf4jLogConsumer(logger.underlying))
    .withExposedPorts(9200)
    .waitingFor(helthyClusterWaitStrategy)

  def host: String = container.getContainerIpAddress

  def port: Integer = container.getMappedPort(9200)

  val adminClient: RestClient =
    new RestClient(true, host, port, Optional.of(Tuple.from(adminCredentials._1, adminCredentials._2)))

  private def createImage(pluginFile: File) = {
    val dockerImage =
      if (Version.greaterOrEqualThan(esVersion, 6, 3, 0)) "docker.elastic.co/elasticsearch/elasticsearch-oss"
      else "docker.elastic.co/elasticsearch/elasticsearch"
    val rorConfigFileName = "readonlyrest.yml"
    val log4j2FileName = "log4j2.properties"
    val javaOptionsFileName = "jvm.options"
    val keystoreFileName = "keystore.jks"

    new ImageFromDockerfile()
      .withFileFromFile(pluginFile.getAbsolutePath, pluginFile)
      .withFileFromFile(rorConfigFileName, rorConfigFile)
      .withFileFromFile(log4j2FileName, ContainerUtils.getResourceFile("/" + log4j2FileName))
      .withFileFromFile(keystoreFileName, ContainerUtils.getResourceFile("/" + keystoreFileName))
      .withFileFromFile(javaOptionsFileName, ContainerUtils.getResourceFile("/" + javaOptionsFileName))
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        builder
          .from(dockerImage + ":" + esVersion)
          .env("TEST_VAR", "dev")
          .copy(pluginFile.getAbsolutePath, "/tmp/")
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
            .run("egrep -v 'node\\.name|initial_master_nodes' /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
            .run("echo 'node.name: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run("echo 'cluster.initial_master_nodes: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml")
        }

        builder.user("elasticsearch")
          .env("ES_JAVA_OPTS", "-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandoms -Dcom.unboundid.ldap.sdk.debug.enabled=true")
          .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install file:///tmp/" + pluginFile.getName)

        logger.info("Dockerfile\n" + builder.build)
      })
  }

  private def helthyClusterWaitStrategy: WaitStrategy = new AbstractWaitStrategy {
    override def waitUntilReady(): Unit = {
      val retryPolicy = new RetryPolicy[util.List[util.Map[String, AnyRef]]]()
        .handleIf(emptyEntriesResultPredicate)
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10))
      Failsafe.`with`(retryPolicy).get(() => call("/" + indexName + "/_search", restClient))
    }

    private def emptyEntriesResultPredicate = new BiPredicate[Boolean, Throwable] {

    }

    private def checkClusterHealth() = {
      val clusterHealthRequest = new HttpGet(adminClient.from("_cluster/health")))
      bracket(adminClient.execute(clusterHealthRequest)) { response =>
        result.getStatusLine.getStatusCode match {
          case 200 =>
            GsonHelper
          case _ =>
            false
        }
      }
    }

  }
}

private object ReadonlyRestEsContainer {
  type OTCContainer = OTCGenericContainer[T] forSome {type T <: OTCGenericContainer[T]}
}