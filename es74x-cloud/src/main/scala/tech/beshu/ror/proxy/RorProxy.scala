/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import better.files.File
import cats.data.EitherT
import cats.effect.{ContextShift, IO}
import com.twitter.finagle.Http
import monix.eval.Task
import monix.execution.schedulers.SchedulerService
import org.apache.http.HttpHost
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.proxy.RorProxy.CloseHandler
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.{EsCode, EsRestServiceSimulator}
import tech.beshu.ror.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps.{twitterFutureToIo, _}

trait RorProxy  {

  implicit val mainScheduler: SchedulerService = monix.execution.Scheduler.cached("ror-proxy", 10, 20)
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
    implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), config.esConfigFile, threadPool))
      server = Http.server.serve(s":${config.proxyPort}", new ProxyRestInterceptorService(simulator))
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
    new RestHighLevelClient(RestClient.builder(HttpHost.create(config.targetEsNode)))
  }
}

object RorProxy {
  type CloseHandler = () => IO[Unit]
  type ProxyAppWithCloseHandler = (RorProxy, RorProxy.CloseHandler)

  final case class Config(targetEsNode: String,
                          proxyPort: Int,
                          esConfigFile: File)

}