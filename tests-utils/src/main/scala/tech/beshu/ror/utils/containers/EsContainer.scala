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

import java.util.Optional
import java.util.function.Consumer

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.apache.http.message.BasicHeader
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.EsContainer.Credentials
import tech.beshu.ror.utils.containers.EsContainer.Credentials.{BasicAuth, None, Token}
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.finiteDurationToJavaDuration
import tech.beshu.ror.utils.misc.Tuple

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

abstract class EsContainer(val name: String,
                           val esVersion: String,
                           val startedClusterDependencies: StartedClusterDependencies,
                           val esClusterSettings: EsClusterSettings,
                           image: ImageFromDockerfile)
  extends SingleContainer[GenericContainer[_]]
    with ClientProvider
    with StrictLogging {

  override implicit val container = new org.testcontainers.containers.GenericContainer(image)

  def sslEnabled: Boolean

  def ip: String = container.getContainerIpAddress

  def port: Integer = container.getMappedPort(9200)

  override def client(credentials: Credentials): RestClient = credentials match {
    case BasicAuth(user, password) => new RestClient(sslEnabled, host, port, Optional.of(Tuple.from(user, password)))
    case Token(token) => new RestClient(sslEnabled, "localhost", port, Optional.empty[Tuple[String, String]](), new BasicHeader("Authorization", token))
    case None => new RestClient(sslEnabled, host, port, Optional.empty[Tuple[String, String]]())
  }
}

object EsContainer extends StrictLogging {
  trait Config {
    def clusterName: String
    def nodeName: String
    def nodes: NonEmptyList[String]
    def envs: Map[String, String]
    def esVersion: String
    def xPackSupport: Boolean
    def internodeSslEnabled: Boolean
    def configHotReloadingEnabled: Boolean
    def customRorIndexName: Option[String]
    def externalSslEnabled: Boolean
  }

  def init(esContainer: EsContainer,
           config: EsContainer.Config,
           initializer: ElasticsearchNodeDataInitializer): EsContainer = {

    val logConsumer: Consumer[OutputFrame] = new Slf4jLogConsumer(logger.underlying)
    esContainer.container.setLogConsumers((logConsumer :: Nil).asJava)
    esContainer.container.addExposedPort(9200)
    esContainer.container.addExposedPort(9300)
    esContainer.container.addExposedPort(8000)
    esContainer.container.setWaitStrategy(
      new ElasticsearchNodeWaitingStrategy(config.esVersion, esContainer.name, Coeval(esContainer.adminClient), initializer)
        .withStartupTimeout(3 minutes)
    )
    esContainer.container.setNetwork(Network.SHARED)
    esContainer.container.setNetworkAliases((config.nodeName :: Nil).asJava)
    esContainer
  }

  sealed trait Credentials
  object Credentials {
    final case class BasicAuth(user: String, password: String) extends Credentials
    final case class Token(token: String) extends Credentials
    case object None extends Credentials
  }
}
