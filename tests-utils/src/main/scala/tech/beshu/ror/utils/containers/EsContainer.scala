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
import com.typesafe.scalalogging.{Logger, StrictLogging}
import monix.eval.Coeval
import org.apache.http.message.BasicHeader
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.containers.EsContainer.Credentials
import tech.beshu.ror.utils.containers.EsContainer.Credentials.{BasicAuth, Header, None, Token}
import tech.beshu.ror.utils.containers.images.Elasticsearch
import tech.beshu.ror.utils.containers.logs.CompositeLogConsumer
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.finiteDurationToJavaDuration

import java.util.function.Consumer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

abstract class EsContainer(val esVersion: String,
                           val esConfig: Elasticsearch.Config,
                           val startedClusterDependencies: StartedClusterDependencies,
                           image: ImageFromDockerfile)
  extends SingleContainer[GenericContainer[_]]
    with ClientProvider
    with StrictLogging {

  override implicit val container: GenericContainer[_] = new org.testcontainers.containers.GenericContainer(image)

  def sslEnabled: Boolean

  def ip: String = container.getHost

  def port: Integer = container.getMappedPort(9200)

  def getAddressInInternalNetwork = s"${containerInfo.getConfig.getHostName}:9200"

  override def client(credentials: Credentials): RestClient = credentials match {
    case BasicAuth(user, password) => new RestClient(sslEnabled, ip, port, Some(user, password))
    case Token(token) => new RestClient(sslEnabled, ip, port, Option.empty, new BasicHeader("Authorization", token))
    case Header(name, value) => new RestClient(sslEnabled, ip, port, Option.empty, new BasicHeader(name, value))
    case None => new RestClient(sslEnabled, ip, port, Option.empty)
  }
}

object EsContainer {

  def init(esContainer: EsContainer,
           initializer: ElasticsearchNodeDataInitializer,
           logger: Logger,
           additionalLogConsumer: Option[Consumer[OutputFrame]],
           awaitingReadyStrategy: AwaitingReadyStrategy = AwaitingReadyStrategy.WaitForEsReadiness): EsContainer = {
    val slf4jConsumer = new Slf4jLogConsumer(logger.underlying)
    val logConsumer: Consumer[OutputFrame] = additionalLogConsumer match {
      case Some(additional) => new CompositeLogConsumer(slf4jConsumer, additional)
      case scala.None => slf4jConsumer
    }
    val esClient = Coeval(esContainer.adminClient)
    esContainer.container.setLogConsumers((logConsumer :: Nil).asJava)
    esContainer.container.addExposedPort(9200)
    esContainer.container.addExposedPort(9300)
    esContainer.container.addExposedPort(8000)
    esContainer.container.setWaitStrategy(
      new ElasticsearchNodeWaitingStrategy(esContainer.esVersion, esContainer.esConfig.nodeName, esClient, initializer, awaitingReadyStrategy)
        .withStartupTimeout(5 minutes)
    )
    esContainer.container.setNetwork(Network.SHARED)
    esContainer.container.setNetworkAliases((esContainer.esConfig.nodeName :: Nil).asJava)
    esContainer
  }

  sealed trait Credentials
  object Credentials {
    final case class BasicAuth(user: String, password: String) extends Credentials
    final case class Header(name: String, value: String) extends Credentials
    final case class Token(token: String) extends Credentials
    case object None extends Credentials
  }
}
