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
package tech.beshu.ror.utils.containers.generic

import java.util.Optional
import java.util.function.Consumer

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import com.typesafe.scalalogging.StrictLogging
import monix.eval.Coeval
import org.testcontainers.containers.output.{OutputFrame, Slf4jLogConsumer}
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.images.builder.ImageFromDockerfile
import tech.beshu.ror.utils.containers.generic.RorContainer.adminCredentials
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.finiteDurationToJavaDuration
import tech.beshu.ror.utils.misc.Tuple

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

abstract class RorContainer (val name: String, val esVersion: String, image: ImageFromDockerfile)
  extends SingleContainer[GenericContainer[_]]
    with ClientProvider
    with StrictLogging {

  override implicit val container = new org.testcontainers.containers.GenericContainer(image)

  def sslEnabled: Boolean

  def host: String = container.getContainerIpAddress

  def port: Integer = container.getMappedPort(9200)

  override def client(user: String, password: String): RestClient = new RestClient(sslEnabled, host, port, Optional.of(Tuple.from(user, password)))
}

object RorContainer extends StrictLogging {
  val adminCredentials: (String, String) = ("admin", "container")

  trait Config {
    def nodeName: String
    def nodes: NonEmptyList[String]
    def esVersion: String
    def xPackSupport: Boolean
  }

  def init(rorContainer: RorContainer,
           config: RorContainer.Config,
           initializer: ElasticsearchNodeDataInitializer): RorContainer = {

    val logConsumer: Consumer[OutputFrame] = new Slf4jLogConsumer(logger.underlying)
    rorContainer.container.setLogConsumers((logConsumer :: Nil).asJava)
    rorContainer.container.addExposedPort(9200)
    rorContainer.container.setWaitStrategy(
      new ElasticsearchNodeWaitingStrategy(config.esVersion, rorContainer.name, Coeval(rorContainer.adminClient), initializer)
        .withStartupTimeout(3 minutes)
    )
    rorContainer.container.setNetwork(Network.SHARED)
    rorContainer.container.setNetworkAliases((config.nodeName :: Nil).asJava)
    rorContainer
  }
}



