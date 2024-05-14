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
package tech.beshu.ror.accesscontrol.factory

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.netty.util.HashedWheelTimer
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import org.apache.logging.log4j.scala.Logging
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.netty.channel.DefaultChannelPool
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig}
import sttp.capabilities
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.client3.{Request, Response, SttpBackend}
import sttp.monad.MonadError
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

trait HttpClientsFactory {

  def create(config: Config): HttpClient

  def shutdown(): Unit
}

object HttpClientsFactory {
  type HttpClient = SttpBackend[Task, Any]

  final case class Config(connectionTimeout: PositiveFiniteDuration,
                          requestTimeout: PositiveFiniteDuration,
                          connectionPoolSize: Int Refined Positive,
                          validate: Boolean)

  object Config {
    val default: Config = Config(
      connectionTimeout = positiveFiniteDuration(2, TimeUnit.SECONDS),
      requestTimeout = positiveFiniteDuration(5, TimeUnit.SECONDS),
      connectionPoolSize = positiveInt(30),
      validate = true
    )
  }

}

// todo: remove synchronized, use more sophisticated lock mechanism
class AsyncHttpClientsFactory extends HttpClientsFactory {

  private val existingClients = new CopyOnWriteArrayList[AsyncHttpClient]()
  private val isWorking = AtomicBoolean(true)

  override def create(config: Config): HttpClient = synchronized {
    if (isWorking.get()) {
      val asyncHttpClient = newAsyncHttpClient(config)
      existingClients.add(asyncHttpClient)
      new LoggingSttpBackend[Task, Any](AsyncHttpClientCatsBackend.usingClient[Task](asyncHttpClient))
    } else {
      throw new IllegalStateException("Cannot create http client - factory was closed")
    }
  }

  override def shutdown(): Unit = synchronized {
    isWorking.set(false)
    existingClients.iterator().asScala.foreach(_.close())
  }

  private def newAsyncHttpClient(config: Config) = {
    val timer = new HashedWheelTimer
    val pool = new DefaultChannelPool(60000, -1, DefaultChannelPool.PoolLeaseStrategy.FIFO, timer, -1)
    asyncHttpClient {
      new DefaultAsyncHttpClientConfig.Builder()
        .setNettyTimer(timer)
        .setChannelPool(pool)
        .setUseInsecureTrustManager(!config.validate)
        .build()
    }
  }
}

private class LoggingSttpBackend[F[_], +P](delegate: SttpBackend[F, P])
  extends SttpBackend[F, P]
    with Logging {

  override def send[T, R >: P with capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] = {
    responseMonad
      .map(
        responseMonad
          .handleError(delegate.send(request)) {
            case e: Exception =>
              logger.error(s"Exception when sending request: $request", e)
              responseMonad.error(e)
          }
      ) { response =>
        if (response.isServerError) {
          logger.warn(s"For request: $request got response: $response")
        } else {
          logger.debug(s"For request: $request got response: $response")
        }
        response
      }
  }

  override def close(): F[Unit] = delegate.close()

  override def responseMonad: MonadError[F] = delegate.responseMonad
}