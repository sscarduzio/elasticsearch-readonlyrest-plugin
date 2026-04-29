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
import org.apache.hc.client5.http.ssl.{ClientTlsStrategyBuilder, HostnameVerificationPolicy, NoopHostnameVerifier, TrustAllStrategy}
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.apache.hc.core5.util.Timeout
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.SimpleHttpClient.Config
import tech.beshu.ror.accesscontrol.utils.AsyncOps.deferFuture
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.concurrent.{Future, Promise}
import scala.language.postfixOps

private class ApacheBasedSimpleHttpClient[F[_] : Async : ContextShift](client: CloseableHttpAsyncClient)
  extends SimpleHttpClient[F] with RequestIdAwareLogging {

  override def send(request: HttpClient.Request)
                   (implicit requestId: RequestId): F[HttpClient.Response] = {
    val method = request.method match {
      case Method.Get => "GET"
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
      .handleError(e => noRequestIdLogger.error("Error closing Apache CloseableHttpAsyncClient", e))

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

private class ApacheBasedSimpleHttpClientCreator[F[_] : Async : ContextShift]
  extends SimpleHttpClientCreator[F, ApacheBasedSimpleHttpClient[F]]
    with RequestIdAwareLogging {

  override def create(config: Config): ApacheBasedSimpleHttpClient[F] = {
    new ApacheBasedSimpleHttpClient(newCloseableHttpAsyncClient(config))
  }

  private def newCloseableHttpAsyncClient(config: Config): CloseableHttpAsyncClient = {
    try {
      val connectionConfig =
        ConnectionConfig.custom()
          .setConnectTimeout(Timeout.ofMilliseconds(config.connectionTimeout.value.toMillis))
          .build()

      val connManagerBuilder = PoolingAsyncClientConnectionManagerBuilder.create()
        .setDefaultConnectionConfig(connectionConfig)
        .setMaxConnTotal(config.connectionPoolSize.value)
        .setMaxConnPerRoute(config.connectionPoolSize.value)

      if (!config.validate) {
        val sslContext = SSLContextBuilder.create()
          .loadTrustMaterial(TrustAllStrategy.INSTANCE)
          .build()
        val tlsStrategy = ClientTlsStrategyBuilder.create()
          .setSslContext(sslContext)
          .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .buildAsync()
        connManagerBuilder.setTlsStrategy(tlsStrategy)
      }

      val connManager = connManagerBuilder.build()

      val requestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.requestTimeout.value.toMillis))
        .setResponseTimeout(Timeout.ofMilliseconds(config.requestTimeout.value.toMillis))
        .build()

      val client = HttpAsyncClients.custom()
        .setConnectionManager(connManager)
        .setDefaultRequestConfig(requestConfig)
        .build()

      client.start()
      client
    } catch {
      case ex: Throwable =>
        noRequestIdLogger.error("Failed to create Apache HttpAsyncClient", ex)
        throw ex
    }
  }

}
