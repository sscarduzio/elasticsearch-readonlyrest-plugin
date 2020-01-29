/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import java.io.File

import cats.data.EitherT
import cats.effect.{ContextShift, ExitCode, IO, IOApp, Resource}
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
import tech.beshu.ror.proxy.RorProxy.CloseHandler
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.{EsCode, EsRestServiceSimulator}
import tech.beshu.ror.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps._

object Boot extends IOApp with RorProxy with Logging {

  override val config: RorProxy.Config = RorProxy.Config(
    targetEsNode = "http://localhost:9200",
    proxyPort = "5000",
    rorConfigFile = null
  )

  override def run(args: List[String]): IO[ExitCode] = {
    start
      .flatMap {
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
  }
}

trait RorProxy {

  implicit protected def contextShift: ContextShift[IO]

  def config: RorProxy.Config

  def start: IO[Either[StartingFailure, CloseHandler]] = {
    for {
      _ <- IO(EsCode.improve())
      startingResult <- runServer
    } yield startingResult
  }

  private def runServer: IO[Either[StartingFailure, CloseHandler]] = {
    val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
    val esClient = createEsHighLevelClient()
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), threadPool))
      server = Http.server.serve(s":${config.proxyPort}", new ProxyRestInterceptorService(simulator))
    } yield () =>
      for {
        _ <- twitterFutureToIo(server.close())
        _ <- simulator.stop()
        _ <- Task(esClient.close())
        _ <- Task(threadPool.shutdownNow())
      } yield println("Wyczyszczone!!!!!!!!")
    result.value
  }

  private def createEsHighLevelClient() = {
    new RestHighLevelClient(RestClient.builder(HttpHost.create(config.targetEsNode)))
  }

}

object RorProxy {

  type CloseHandler = () => IO[Unit]

  final case class Config(targetEsNode: String,
                          proxyPort: String,
                          rorConfigFile: File)

}
