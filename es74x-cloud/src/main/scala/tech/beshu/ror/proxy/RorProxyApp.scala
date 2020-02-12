/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import better.files.File
import cats.data.EitherT
import cats.effect.{ContextShift, IO}
import com.twitter.finagle.Http
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.HttpHost
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.proxy.RorProxy.CloseHandler
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.{EsCode, EsRestServiceSimulator}
import tech.beshu.ror.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps.{twitterFutureToIo, _}

trait RorProxyApp extends RorProxy {

  implicit protected def contextShift: ContextShift[IO]

  def start: IO[Either[StartingFailure, CloseHandler]] = {
    for {
      _ <- IO(EsCode.improve())
      startingResult <- runServer
    } yield startingResult
  }

  private def runServer: IO[Either[StartingFailure, CloseHandler]] = {
    val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
    val esClient = createEsHighLevelClient()
    val esConfig = config.esConfigFile.getOrElse(File(getClass.getClassLoader.getResource("elasticsearch.yml")))
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), esConfig, threadPool))
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