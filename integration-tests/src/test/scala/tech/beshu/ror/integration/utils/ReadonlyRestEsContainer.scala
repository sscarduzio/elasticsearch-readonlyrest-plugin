package tech.beshu.ror.integration.utils

import java.io.File
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import cats.Id
import cats.implicits._
import com.dimafeng.testcontainers.SingleContainer
import com.typesafe.scalalogging.StrictLogging
import org.apache.http.client.methods.HttpGet
import org.testcontainers.containers.{ContainerLaunchException, GenericContainer, Network}
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.wait.strategy.{AbstractWaitStrategy, WaitStrategy}
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import retry._
import retry.RetryPolicies.{constantDelay, limitRetriesByCumulativeDelay}
import retry.{RetryDetails, RetryPolicy}
import tech.beshu.ror.integration.utils.JavaScalaUtils.bracket
import tech.beshu.ror.integration.utils.ReadonlyRestEsContainer.adminCredentials
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody
import tech.beshu.ror.utils.misc.{Tuple, Version}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class ReadonlyRestEsContainer private(val name: String, underlying: GenericContainer[_])
  extends SingleContainer[GenericContainer[_]] {

  override implicit val container: GenericContainer[_] = underlying

  def host: String = underlying.getContainerIpAddress

  def port: Integer = underlying.getMappedPort(9200)

  def adminClient: RestClient =
    new RestClient(true, host, port, Optional.of(Tuple.from(adminCredentials._1, adminCredentials._2)))
}

object ReadonlyRestEsContainer extends StrictLogging {

  def create(name: String,
             hosts: List[String],
             esVersion: String,
             rorPluginFile: File,
             rorConfigFile: File,
             network: Network): ReadonlyRestEsContainer = {
    logger.info(s"[$name] Creating ROR container ...")
    val rorContainer = new ReadonlyRestEsContainer(
      name,
      new org.testcontainers.containers.GenericContainer(
        createImage(name, hosts, esVersion, rorPluginFile, rorConfigFile)
      )
    )
    val logConsumer: Consumer[OutputFrame] = new Slf4jLogConsumer(logger.underlying)
    rorContainer.container.setLogConsumers((logConsumer :: Nil).asJava)
    rorContainer.container.addExposedPort(9200)
    rorContainer.container.setWaitStrategy(healthyClusterWaitStrategy(rorContainer))
    rorContainer.container.setNetwork(network)
    rorContainer.container.setNetworkAliases((name :: Nil).asJava)
    rorContainer
  }

  val adminCredentials: (String, String) = ("admin", "container")

  private def createImage(nodeName: String,
                          nodes: List[String],
                          esVersion: String,
                          rorPluginFile: File,
                          rorConfigFile: File): ImageFromDockerfile = {
    val dockerImage =
      if (Version.greaterOrEqualThan(esVersion, 6, 3, 0)) "docker.elastic.co/elasticsearch/elasticsearch-oss"
      else "docker.elastic.co/elasticsearch/elasticsearch"
    val rorConfigFileName = "readonlyrest.yml"
    val log4j2FileName = "log4j2.properties"
    val javaOptionsFileName = "jvm.options"
    val keystoreFileName = "keystore.jks"

    new ImageFromDockerfile()
      .withFileFromFile(rorPluginFile.getAbsolutePath, rorPluginFile)
      .withFileFromFile(rorConfigFileName, rorConfigFile)
      .withFileFromFile(log4j2FileName, ContainerUtils.getResourceFile("/" + log4j2FileName))
      .withFileFromFile(keystoreFileName, ContainerUtils.getResourceFile("/" + keystoreFileName))
      .withFileFromFile(javaOptionsFileName, ContainerUtils.getResourceFile("/" + javaOptionsFileName))
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        builder
          .from(dockerImage + ":" + esVersion)
          .env("TEST_VAR", "dev")
          .copy(rorPluginFile.getAbsolutePath, "/tmp/")
          .copy(log4j2FileName, "/usr/share/elasticsearch/config/")
          .copy(keystoreFileName, "/usr/share/elasticsearch/config/")
          .copy(javaOptionsFileName, "/usr/share/elasticsearch/config/")
          .copy(rorConfigFileName, "/usr/share/elasticsearch/config/readonlyrest.yml")
          .run("/usr/share/elasticsearch/bin/elasticsearch-plugin remove x-pack --purge || rm -rf /usr/share/elasticsearch/plugins/*")
          .run("grep -v xpack /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
          .run("echo 'http.type: ssl_netty4' >> /usr/share/elasticsearch/config/elasticsearch.yml")
//          .run("sed -i \"s|debug|info|g\" /usr/share/elasticsearch/config/log4j2.properties")
          .user("root")
          .run("chown elasticsearch:elasticsearch config/*")

        if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
          builder
            .run("egrep -v 'node\\.name|cluster\\.initial_master_nodes|cluster\\.name|network\\.host' /usr/share/elasticsearch/config/elasticsearch.yml > /tmp/xxx.yml && mv /tmp/xxx.yml /usr/share/elasticsearch/config/elasticsearch.yml")
//            .run("echo 'node.name: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml")
//            .run("echo 'cluster.initial_master_nodes: n1' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'node.name: $nodeName' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'network.host: 0.0.0.0' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'discovery.seed_hosts: ${nodes.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.initial_master_nodes: ${nodes.mkString(",")}' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"echo 'cluster.name: test-cluster' >> /usr/share/elasticsearch/config/elasticsearch.yml")
            .run(s"cat /usr/share/elasticsearch/config/elasticsearch.yml")
        }

        builder.user("elasticsearch")
          .env("ES_JAVA_OPTS", "-Xms512m -Xmx512m -Djava.security.egd=file:/dev/./urandoms -Dcom.unboundid.ldap.sdk.debug.enabled=true")
          .run("yes | /usr/share/elasticsearch/bin/elasticsearch-plugin install file:///tmp/" + rorPluginFile.getName)

        logger.info("Dockerfile\n" + builder.build)
      })
  }

  private def healthyClusterWaitStrategy(container: ReadonlyRestEsContainer): WaitStrategy =
    new AbstractWaitStrategy {

      private val startupThreshold = FiniteDuration(startupTimeout.toMillis * 2, TimeUnit.MILLISECONDS) // todo: fixme

      override def waitUntilReady(): Unit = {
        val client = container.adminClient
        val started = retry {
          checkClusterHealth(client).fold(
            throwable => {
              logger.error(s"[${container.name}] Cannot Check cluster health: ${throwable.getLocalizedMessage}")
              false
            },
            identity
          )
        }
        if(!started) {
          throw new ContainerLaunchException(s"Cannot start ROR-ES container [${container.name}]")
        }
      }

      private def retry[A](checkClusterHealthAction: => Boolean) = {
        val policy: RetryPolicy[Id] = limitRetriesByCumulativeDelay(startupThreshold, constantDelay(2 seconds))
        val predicate = (_: Boolean) == true
        def onFailure(failedValue: Boolean, details: RetryDetails): Unit = {
          logger.debug(s"[${container.name}] Cluster not ready yet. Retrying ...")
        }
        retrying(policy, predicate, onFailure) {
          checkClusterHealthAction
        }
      }

      private def checkClusterHealth(client: RestClient) = {
        val clusterHealthRequest = new HttpGet(client.from("_cluster/health"))
        bracket(Try(client.execute(clusterHealthRequest))) { response =>
          response.getStatusLine.getStatusCode match {
            case 200 =>
              val healthJson = deserializeJsonBody(RestClient.bodyFrom(response))
              "green" == healthJson.get("status")
            case _ =>
              false
          }
        }
      }

    }

}