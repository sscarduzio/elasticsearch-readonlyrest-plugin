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

import java.util.concurrent.CopyOnWriteArrayList

import com.softwaremill.sttp.asynchttpclient.cats.AsyncHttpClientCatsBackend
import com.softwaremill.sttp.{MonadError, Request, Response, SttpBackend}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.netty.util.HashedWheelTimer
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import org.apache.logging.log4j.scala.Logging
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.netty.channel.DefaultChannelPool
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.utils.DurationOps._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}

trait HttpClientsFactory {

  def create(config: Config): HttpClient

  def shutdown(): Unit
}

object HttpClientsFactory {
  type HttpClient = SttpBackend[Task, Nothing]

  final case class Config(connectionTimeout: FiniteDuration Refined Positive,
                          requestTimeout: FiniteDuration Refined Positive,
                          connectionPoolSize: Int Refined Positive,
                          validate: Boolean)
  object Config {
    val default: Config = Config(
      connectionTimeout = (2 seconds).toRefinedPositiveUnsafe,
      requestTimeout = (5 seconds).toRefinedPositiveUnsafe,
      connectionPoolSize = refineV[Positive](30).right.get,
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
      new LoggingSttpBackend[Task, Nothing](AsyncHttpClientCatsBackend.usingClient(asyncHttpClient))
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

private class LoggingSttpBackend[R[_], S](delegate: SttpBackend[R, S])
  extends SttpBackend[R, S]
    with Logging {

  override def send[T](request: Request[T, S]): R[Response[T]] = {
    responseMonad
      .map(
        responseMonad
          .handleError(delegate.send(request)) {
            case e: Exception =>
              logger.error(s"Exception when sending request: $request", e)
              responseMonad.error(e)
          }
      ) { response =>
        if (response.isSuccess) {
          logger.debug(s"For request: $request got response: $response")
        } else {
          logger.warn(s"For request: $request got response: $response")
        }
        response
      }
  }

  override def close(): Unit = delegate.close()

  override def responseMonad: MonadError[R] = delegate.responseMonad
}