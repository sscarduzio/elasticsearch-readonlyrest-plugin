/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.twitter.finagle.Http
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.HttpHost
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.{EsCode, EsRestServiceSimulator}
import tech.beshu.ror.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps._

object Boot extends IOApp with Logging {

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      _ <- IO(EsCode.improve())
      startingResult <- runServer
      exitCode <- startingResult match {
        case Right(closeHandler) =>
          val proxyApp = Resource.make(IO(closeHandler))(handler =>
            IO.suspend(handler())
          )
          proxyApp.use(_ => IO.never).as(ExitCode.Success)
        case Left(startingFailure) =>
          val errorMessage = s"Cannot start ReadonlyREST proxy: ${startingFailure.message}"
          startingFailure.throwable match {
            case Some(ex) => logger.error(errorMessage, ex)
            case None => logger.error(errorMessage)
          }
          IO.pure(ExitCode.Error)
      }
    } yield exitCode
  }

  private def runServer: IO[Either[StartingFailure, CloseHandler]] = {
    val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
    val esClient = createEsHighLevelClient()
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), threadPool))
      server = Http.server.serve(":5000", new ProxyRestInterceptorService(simulator)) // todo: from config
    } yield () =>
      for {
        _ <- twitterFutureToIo(server.close())
        _ <- simulator.stop()
        _ <- Task(esClient.close())
        _ <- Task(threadPool.shutdownNow())
      } yield ()
    result.value
  }

  private def createEsHighLevelClient() = {
    new RestHighLevelClient(RestClient.builder(HttpHost.create("http://localhost:9200"))) // todo: from config
  }

  private type CloseHandler = () => IO[Unit]

}
