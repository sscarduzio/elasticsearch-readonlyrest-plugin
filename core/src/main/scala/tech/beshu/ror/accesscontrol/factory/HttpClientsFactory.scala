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

import cats.effect.Async
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.lemonlabs.uri.Url
import io.netty.util.HashedWheelTimer
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import org.apache.logging.log4j.scala.Logging
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.netty.channel.DefaultChannelPool
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.{CopyOnWriteArrayList, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.language.postfixOps

trait HttpClientsFactory {

  def create(config: Config): HttpClient
  def shutdown(): Unit
}

object HttpClientsFactory {
  type HttpClient = SimpleHttpClient[Task]

  object HttpClient {
    sealed trait Method
    object Method {
      case object Get extends Method
      case object Post extends Method
    }

    final case class Request(method: Method,
                             url: Url,
                             headers: Map[String, String])

    final case class Response(status: Int,
                              body: String)
  }

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
      new LoggingSimpleHttpClient[Task](new AsyncBasedSimpleHttpClient(asyncHttpClient))
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

private class LoggingSimpleHttpClient[F[_] : Async](delegate: SimpleHttpClient[F])
  extends SimpleHttpClient[F] with Logging {

  override def send(request: HttpClient.Request): F[HttpClient.Response] = {
    delegate
      .send(request)
      .recoverWith { case e: Throwable =>
        logger.error(s"Exception when sending request: ${request.show}", e)
        Async[F].raiseError(e)
      }
      .map { response =>
        if (response.status / 100 == 5) {
          logger.warn(s"For request: ${request.show}  got response: ${response.show}")
        } else {
          logger.debug(s"For request: ${request.show} got response: ${response.show}")
        }
        response
      }
  }

  override def close(): F[Unit] = delegate.close()
}

class AsyncBasedSimpleHttpClient(asyncHttpClient: AsyncHttpClient) extends SimpleHttpClient[Task] {

  override def send(request: HttpClient.Request): Task[HttpClient.Response] = {
    val asyncRequestBase = request.method match {
      case Method.Get => asyncHttpClient.prepareGet(request.url.toStringRaw)
      case Method.Post => asyncHttpClient.preparePost(request.url.toStringRaw)
    }
    val asyncRequest = request.headers.foldLeft(asyncRequestBase) { (soFar, header) => soFar.setHeader(header._1, header._2) }.build()
    Task
      .deferFuture(asyncHttpClient.executeRequest(asyncRequest).toCompletableFuture.asScala)
      .map { response =>
        HttpClient.Response(
          status = response.getStatusCode,
          body = response.getResponseBody,
        )
      }
  }

  override def close(): Task[Unit] = {
    Task.delay(asyncHttpClient.close())
  }
}

trait SimpleHttpClient[F[_]] {
  def send(request: HttpClient.Request): F[HttpClient.Response]
  def close(): F[Unit]
}
