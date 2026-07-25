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

import cats.effect.{Async, ContextShift}
import cats.implicits.{catsSyntaxApplicativeError, toFunctorOps}
import org.apache.hc.client5.http.async.methods.{SimpleHttpRequest, SimpleHttpResponse}
import org.apache.hc.client5.http.config.{ConnectionConfig, RequestConfig}
import org.apache.hc.client5.http.impl.async.{CloseableHttpAsyncClient, HttpAsyncClients}
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.{
  ClientTlsStrategyBuilder,
  HostnameVerificationPolicy,
  NoopHostnameVerifier,
  TrustAllStrategy
}
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.reactor.IOReactorConfig
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.apache.hc.core5.util.{TimeValue, Timeout}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.accesscontrol.utils.AsyncOps.deferFuture
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.util.control.Exception.catching
import scala.util.control.NonFatal

private class ApacheBasedSimpleHttpClient[F[_]: Async: ContextShift](client: CloseableHttpAsyncClient)
    extends SimpleHttpClient[F]
    with RequestIdAwareLogging {

  override def send(request: HttpClient.Request)(
      implicit requestId: RequestId
  ): F[HttpClient.Response] = {
    val method = request.method match {
      case Method.Get  => "GET"
      case Method.Post => "POST"
    }
    val httpRequest = request.headers.foldLeft(SimpleHttpRequest.create(method, request.url.toStringRaw)) {
      case (req, (k, v)) =>
        req.setHeader(k, v)
        req
    }
    Async[F]
      .deferFuture(client.executeToFuture(httpRequest))
      .map { response =>
        HttpClient.Response(
          status = response.getCode,
          body = Option(response.getBodyText).getOrElse("") // getBodyText returns null for empty-body responses
        )
      }
  }

  override def close(): F[Unit] =
    Async[F]
      .delay(client.close())
      .handleError { case NonFatal(e) => noRequestIdLogger.error("Error closing Apache CloseableHttpAsyncClient", e) }

}

extension (client: CloseableHttpAsyncClient)

  def executeToFuture(request: SimpleHttpRequest): Future[SimpleHttpResponse] = {
    val promise = Promise[SimpleHttpResponse]()
    client.execute(
      request,
      new FutureCallback[SimpleHttpResponse] {
        override def completed(result: SimpleHttpResponse): Unit = promise.success(result)

        override def failed(ex: Exception): Unit = promise.failure(ex)

        override def cancelled(): Unit = promise.failure(new RuntimeException("HTTP request cancelled"))
      }
    )
    promise.future
  }

private class ApacheBasedSimpleHttpClientCreator[F[_]: Async: ContextShift]
    extends SimpleHttpClientCreator[F, ApacheBasedSimpleHttpClient[F]]
    with RequestIdAwareLogging {

  override def create(config: Config): ApacheBasedSimpleHttpClient[F] = {
    new ApacheBasedSimpleHttpClient(newCloseableHttpAsyncClient(config))
  }

  private def newCloseableHttpAsyncClient(config: Config): CloseableHttpAsyncClient = {
    try {
      checkJdkNetAvailability()
      val connectionConfig =
        ConnectionConfig
          .custom()
          .setConnectTimeout(Timeout.ofMilliseconds(config.connectionTimeout.value.toMillis))
          // Compensates for the TCP keep-alive tuning we can't use below (see ioReactorConfig comment):
          // a pooled connection idle longer than this is checked (cheap liveness probe) before reuse,
          // so a peer that died while idle gets evicted instead of failing the next real request.
          .setValidateAfterInactivity(TimeValue.ofSeconds(2))
          .build()

      val connManagerBuilder = PoolingAsyncClientConnectionManagerBuilder
        .create()
        .setDefaultConnectionConfig(connectionConfig)
        .setMaxConnTotal(config.connectionPoolSize.value)
        .setMaxConnPerRoute(config.connectionPoolSize.value)

      if (!config.validate) {
        val sslContext = SSLContextBuilder
          .create()
          .loadTrustMaterial(TrustAllStrategy.INSTANCE)
          .build()
        val tlsStrategy = ClientTlsStrategyBuilder
          .create()
          .setSslContext(sslContext)
          .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .buildAsync()
        connManagerBuilder.setTlsStrategy(tlsStrategy)
      }

      val connManager = connManagerBuilder.build()

      val requestConfig = RequestConfig
        .custom()
        .setConnectionRequestTimeout(
          Timeout.ONE_MILLISECOND
        ) // fail fast when pool is exhausted (1ms, because 0ms means infinite wait)
        .setResponseTimeout(Timeout.ofMilliseconds(config.requestTimeout.value.toMillis))
        .build()

      // httpcore5 only calls the permission-gated jdk.net.ExtendedSocketOptions.TCP_KEEPIDLE/
      // TCP_KEEPINTERVAL/TCP_KEEPCOUNT setOption()s when the corresponding IOReactorConfig value is > 0.
      // Elasticsearch's plugin installer rejects a "jdk.net.NetworkPermission" grant outright on ES 7.11+
      // (PolicyUtil.validatePolicyPermissionsForJar treats it as illegal), so without a grant those calls
      // throw an AccessControlException under the SecurityManager, breaking every outbound HTTP call
      // (external_authentication, JWT remote validation, proxy auth). Setting all three to 0 means the
      // conditions are never true, so those calls are never made and no permission is ever needed.
      // SO_KEEPALIVE itself is a standard socket option (no special permission), so leaving it on still
      // gets idle pooled connections the OS's own default keep-alive timing, just without httpcore5's
      // faster custom tuning.
      val ioReactorConfig = IOReactorConfig
        .custom()
        .setSoKeepAlive(true)
        .setTcpKeepIdle(0)
        .setTcpKeepInterval(0)
        .setTcpKeepCount(0)
        .build()

      val client = HttpAsyncClients
        .custom()
        .setIOReactorConfig(ioReactorConfig)
        .setConnectionManager(connManager)
        .setDefaultRequestConfig(requestConfig)
        .build()

      client.start()
      client
    } catch {
      case NonFatal(ex) =>
        noRequestIdLogger.error("Failed to create Apache HttpAsyncClient", ex)
        throw ex
    }
  }

  private def checkJdkNetAvailability(): Unit = {
    catching(classOf[ClassNotFoundException], classOf[NoClassDefFoundError])
      .either(Class.forName("jdk.net.Sockets"))
      .left
      .foreach { ex =>
        val message =
          "jdk.net module is not accessible (jdk.net.Sockets cannot be loaded). " +
            "Apache HttpClient 5 requires it. " +
            "Add '--add-modules=jdk.net' to JVM startup options " +
            "(ES_JAVA_OPTS environment variable or jvm.options.d/ror.options file)."
        noRequestIdLogger.error(message)
        throw new RuntimeException(message, ex)
      }
  }

}
