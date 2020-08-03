/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy

import java.security.cert.X509Certificate

import better.files.File
import cats.data.EitherT
import cats.effect.{ContextShift, IO}
import com.twitter.finagle.Http
import javax.net.ssl.{SSLContext, X509TrustManager}
import monix.eval.Task
import monix.execution.schedulers.SchedulerService
import org.apache.http.{HttpHost, Header => ApacheHttpHeader}
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.http.message.BasicHeader
import org.elasticsearch.client.{RestClient, RestHighLevelClient}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.node.Node
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.{BasicAuth, Credentials, Header}
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.proxy.RorProxy.CloseHandler
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.{EsCode, EsRestServiceSimulator}
import tech.beshu.ror.proxy.server.ProxyRestInterceptorService
import tech.beshu.ror.utils.ScalaOps.{twitterFutureToIo, _}

trait RorProxy {

  implicit val mainScheduler: SchedulerService = monix.execution.Scheduler.cached("ror-proxy", 10, 20)

  implicit protected def contextShift: ContextShift[IO]

  implicit def envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  def start(config: RorProxy.Config): IO[Either[StartingFailure, CloseHandler]] = {
    for {
      _ <- IO(EsCode.improve())
      startingResult <- runServer(config)
    } yield startingResult
  }

  private def runServer(config: RorProxy.Config): IO[Either[StartingFailure, CloseHandler]] = {
    val threadPool: ThreadPool = new ThreadPool(
      Settings
        .builder()
        .put(Node.NODE_NAME_SETTING.getKey, "proxy")
        .build()
    )
    val esClient = createEsHighLevelClient(config)
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create(new RestHighLevelClientAdapter(esClient), config.esConfigFile, threadPool))
      server = Http.server.serve(s":${config.proxyPort}", new ProxyRestInterceptorService(simulator, config))
    } yield () =>
      for {
        _ <- twitterFutureToIo(server.close())
        _ <- simulator.stop()
        _ <- Task(esClient.close())
        _ <- Task(threadPool.shutdownNow())
      } yield ()
    result.value
  }

  private def createEsHighLevelClient(config: RorProxy.Config) = new RestHighLevelClient(
    RestClient
      .builder(
        new HttpHost(config.esHost, config.esPort, "https"),
        new HttpHost(config.esHost, config.esPort, "http")
      )
      .setDefaultHeaders(defaultHeadersFrom(config))
      .setHttpClientConfigCallback(
        (httpClientBuilder: HttpAsyncClientBuilder) => {
          // todo: at the moment there is no hostname verification and all certs are considered as trusted
          val trustAllCerts = new X509TrustManager() {
            override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
            override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
            override def getAcceptedIssuers: Array[X509Certificate] = null
          }
          val sslContext = SSLContext.getInstance("TLS")
          sslContext.init(null, Array(trustAllCerts), null)
          httpClientBuilder
            .setSSLContext(sslContext)
            .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
        }
      )
  )

  private def defaultHeadersFrom(config: RorProxy.Config): Array[ApacheHttpHeader] = config.superUserCredentials match {
    case Some(credentials) => Array(toApacheHeader(BasicAuth(credentials).header))
    case None => Array()
  }

  private def toApacheHeader(header: Header): ApacheHttpHeader = new BasicHeader(header.name.value.value, header.value.value)
}

object RorProxy {
  type CloseHandler = () => IO[Unit]
  type ProxyAppWithCloseHandler = (RorProxy, RorProxy.CloseHandler)

  final case class Config(proxyPort: Int,
                          esHost: String,
                          esPort: Int,
                          esConfigFile: File,
                          superUserCredentials: Option[Credentials]) {
    val esAddress: String = s"$esHost:$esPort"
  }

}