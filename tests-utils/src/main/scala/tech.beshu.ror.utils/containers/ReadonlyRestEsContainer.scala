package tech.beshu.ror.utils.containers

import java.io.File
import java.util.Optional
import java.util.function.Consumer

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.{GenericContainer, Network}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Tuple
import ReadonlyRestEsContainer.adminCredentials
import tech.beshu.ror.utils.misc.ScalaUtils.finiteDurationToJavaDuration

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class ReadonlyRestEsContainer private(val name: String, underlying: GenericContainer[_])
  extends SingleContainer[GenericContainer[_]]
  with StrictLogging {

  logger.info(s"[$name] Creating ROR container ...")

  override implicit val container: GenericContainer[_] = underlying

  def host: String = underlying.getContainerIpAddress

  def port: Integer = underlying.getMappedPort(9200)

  def adminClient: RestClient =
    new RestClient(true, host, port, Optional.of(Tuple.from(adminCredentials._1, adminCredentials._2)))

  def client(user: String, password: String): RestClient =
    new RestClient(true, host, port, Optional.of(Tuple.from(user, password)))
}

object ReadonlyRestEsContainer extends StrictLogging {

  val adminCredentials: (String, String) = ("admin", "container")

  def create(nodeName: String,
             seedNodes: NonEmptyList[String],
             esVersion: String,
             rorPluginFile: File,
             rorConfigFile: File,
             initializer: ElasticsearchNodeDataInitializer): ReadonlyRestEsContainer = {
    val rorContainer = new ReadonlyRestEsContainer(
      nodeName,
      new org.testcontainers.containers.GenericContainer(
        ESWithReadonlyRestImage.create(nodeName, seedNodes, esVersion, rorPluginFile, rorConfigFile)
      )
    )
    val logConsumer: Consumer[OutputFrame] = new Slf4jLogConsumer(logger.underlying)
    rorContainer.container.setLogConsumers((logConsumer :: Nil).asJava)
    rorContainer.container.addExposedPort(9200)
    rorContainer.container.setWaitStrategy(
      new ElasticsearchNodeWaitingStrategy(rorContainer.name, Coeval(rorContainer.adminClient), initializer)
        .withStartupTimeout(5 minutes)
    )
    rorContainer.container.setNetwork(Network.SHARED)
    rorContainer.container.setNetworkAliases((nodeName :: Nil).asJava)
    rorContainer
  }

}