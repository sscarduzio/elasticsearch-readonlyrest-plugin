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

import com.dimafeng.testcontainers.SingleContainer
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.apache.http.message.BasicHeader
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.{BindMode, GenericContainer, Network}
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.containers.EsContainer.Credentials.{BasicAuth, Header, None, Token}
import tech.beshu.ror.utils.containers.EsContainer.{Credentials, EsContainerImplementation}
import tech.beshu.ror.utils.containers.images.{DockerImageCreator, Elasticsearch}
import tech.beshu.ror.utils.containers.logs.CompositeLogConsumer
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.containers.windows.WindowsPseudoEsContainer
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs
import tech.beshu.ror.utils.misc.ScalaUtils.finiteDurationToJavaDuration

import java.util.function.Consumer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

abstract class EsContainer(val esVersion: String,
                           val esConfig: Elasticsearch.Config,
                           val startedClusterDependencies: StartedClusterDependencies,
                           val elasticsearch: Elasticsearch,
                           val initializer: ElasticsearchNodeDataInitializer,
                           val additionalLogConsumer: Option[Consumer[OutputFrame]] = scala.None,
                           val awaitingReadyStrategy: AwaitingReadyStrategy = AwaitingReadyStrategy.WaitForEsReadiness)
  extends SingleContainer[GenericContainer[_]]
    with ClientProvider
    with StrictLogging {

  private val esClient = Coeval(adminClient)

  private val waitStrategy = new ElasticsearchNodeWaitingStrategy(esVersion, esConfig.nodeName, esClient, initializer, awaitingReadyStrategy)

  private val containerImplementation: EsContainerImplementation = {
    OsUtils.currentOs match {
      case CurrentOs.Windows =>
        EsContainerImplementation.Windows(
          container = new WindowsPseudoEsContainer(elasticsearch, waitStrategy, additionalLogConsumer),
        )
      case CurrentOs.OtherThanWindows =>
        val esImage = DockerImageCreator.create(elasticsearch)
        val container = new org.testcontainers.containers.GenericContainer(esImage)
        val slf4jConsumer = new Slf4jLogConsumer(logger.underlying)
        val logConsumer: Consumer[OutputFrame] = additionalLogConsumer match {
          case Some(additional) => new CompositeLogConsumer(slf4jConsumer, additional)
          case scala.None => slf4jConsumer
        }
        container.setLogConsumers((logConsumer :: Nil).asJava)
        container.addExposedPort(9200)
        container.addExposedPort(9300)
        container.addExposedPort(8000)
        container.setWaitStrategy(waitStrategy.withStartupTimeout(5 minutes))
        container.setNetwork(Network.SHARED)
        container.setNetworkAliases((esConfig.nodeName :: Nil).asJava)
        EsContainerImplementation.Linux(
          esImage = esImage,
          container = container
        )
    }
  }

  override implicit val container: GenericContainer[_] = containerImplementation match {
    case EsContainerImplementation.Windows(container) => container
    case EsContainerImplementation.Linux(esImage, container) => container
  }

  def removeImage(): Unit = {
    containerImplementation match {
      case EsContainerImplementation.Windows(_) =>
        ()
      case EsContainerImplementation.Linux(esImage, _) =>
        dockerClient.removeImageCmd(esImage.get()).withForce(true).exec()
    }
  }

  def sslEnabled: Boolean

  def ip: String = containerImplementation match {
    case EsContainerImplementation.Windows(_) => "localhost"
    case EsContainerImplementation.Linux(_, container) => container.getHost
  }

  def port: Integer = containerImplementation match {
    case EsContainerImplementation.Windows(container) => container.getPort
    case EsContainerImplementation.Linux(_, container) => container.getMappedPort(9200)
  }

  def getAddressInInternalNetwork: String = containerImplementation match {
    case EsContainerImplementation.Windows(container) =>
      s"localhost:${container.getPort}"
    case EsContainerImplementation.Linux(_, container) =>
      s"${containerInfo.getConfig.getHostName}:9200"
  }

  override def client(credentials: Credentials): RestClient = credentials match {
    case BasicAuth(user, password) => new RestClient(sslEnabled, ip, port, Some(user, password))
    case Token(token) => new RestClient(sslEnabled, ip, port, Option.empty, new BasicHeader("Authorization", token))
    case Header(name, value) => new RestClient(sslEnabled, ip, port, Option.empty, new BasicHeader(name, value))
    case None => new RestClient(sslEnabled, ip, port, Option.empty)
  }

}

object EsContainer {
  sealed trait Credentials

  object Credentials {
    final case class BasicAuth(user: String, password: String) extends Credentials

    final case class Header(name: String, value: String) extends Credentials

    final case class Token(token: String) extends Credentials

    case object None extends Credentials
  }

  sealed trait EsContainerImplementation

  object EsContainerImplementation {
    final case class Windows(container: WindowsPseudoEsContainer) extends EsContainerImplementation

    final case class Linux(esImage: ImageFromDockerfile, container: GenericContainer[_]) extends EsContainerImplementation
  }

}
