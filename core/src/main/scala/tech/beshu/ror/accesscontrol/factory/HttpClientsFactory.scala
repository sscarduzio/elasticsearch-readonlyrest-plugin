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
import cats.implicits.{catsSyntaxApplicativeError, toFunctorOps}
import io.lemonlabs.uri.Url
import monix.eval.Task
import monix.execution.atomic.AtomicBoolean
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.language.postfixOps

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

  def default() = new HttpClientsFactory(new ApacheBasedSimpleHttpClientCreator[Task])

}

class HttpClientsFactory(httpClientCreator: SimpleHttpClientCreator[Task, HttpClient])
  extends RequestIdAwareLogging {

  private object lock
  private val existingClients = new CopyOnWriteArrayList[SimpleHttpClient[Task]]()
  private val isWorking = AtomicBoolean(true)

  def create(config: Config): HttpClient = lock.synchronized {
    if (isWorking.get()) {
      val client = httpClientCreator.create(config)
      existingClients.add(client)
      new LoggingSimpleHttpClient[Task](client)
    } else {
      throw new IllegalStateException("Cannot create http client - factory was closed")
    }
  }

  def shutdown(): Task[Unit] = {
    val clients = lock.synchronized {
      isWorking.set(false)
      existingClients.iterator().asScala.toList
    }
    Task.parSequenceUnordered(clients.map(_.close())).void
  }

}

private class LoggingSimpleHttpClient[F[_] : Async](delegate: SimpleHttpClient[F])
  extends SimpleHttpClient[F] with RequestIdAwareLogging {

  override def send(request: HttpClient.Request)
                   (implicit requestId: RequestId): F[HttpClient.Response] = {
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
