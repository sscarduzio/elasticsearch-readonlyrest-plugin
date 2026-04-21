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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import io.lemonlabs.uri.Url
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.concurrent.TimeUnit
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

  val enableBenchmark: Boolean = false
  val numberOfBenchmarkRequests: Int = 20000

  def default(): HttpClientsFactory = {
    val delegate = new ApacheHttpClientsFactory
    if (enableBenchmark) {
      new HttpClientsFactory {
        override def create(config: Config): HttpClient = new BenchmarkingHttpClient[Task](delegate.create(config), numberOfBenchmarkRequests)
        override def shutdown(): Unit = delegate.shutdown()
      }
    } else delegate

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

trait SimpleHttpClient[F[_]] {
  def send(request: HttpClient.Request)
          (implicit requestId: RequestId): F[HttpClient.Response]
  def close(): F[Unit]
}

private class BenchmarkingHttpClient[F[_] : Async](delegate: SimpleHttpClient[F], iterations: Int)
  extends SimpleHttpClient[F] with RequestIdAwareLogging {

  override def send(request: HttpClient.Request)
                   (implicit requestId: RequestId): F[HttpClient.Response] = {
    val program = List.fill(iterations)(delegate.send(request)).sequence
    for {
      _ <- Async[F].pure(println(s"Starting benchmark with $iterations requests"))
      start <- Async[F].pure(System.nanoTime())
      _ <- program
      end <- Async[F].pure(System.nanoTime())
      totalMs = (end - start) / 1e6
      avgMs = totalMs / iterations
      rps = iterations / (totalMs / 1000)
      _ <- Async[F].pure {
        println(s"Total time: ${totalMs} ms")
        println(s"Avg latency: ${avgMs} ms")
        println(s"Throughput: ${rps} req/s")
      }
      response <- delegate.send(request)
    } yield response
  }

  override def close(): F[Unit] = delegate.close()
}
