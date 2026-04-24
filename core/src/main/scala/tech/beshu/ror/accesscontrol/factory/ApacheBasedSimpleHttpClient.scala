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

import monix.eval.Task
import org.apache.hc.client5.http.async.methods.{SimpleHttpRequest, SimpleHttpResponse}
import org.apache.hc.client5.http.config.{ConnectionConfig, RequestConfig}
import org.apache.hc.client5.http.impl.async.{CloseableHttpAsyncClient, HttpAsyncClients}
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.{ClientTlsStrategyBuilder, NoopHostnameVerifier, TrustAllStrategy}
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.ssl.SSLContextBuilder
import org.apache.hc.core5.util.Timeout
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.concurrent.Promise
import scala.language.postfixOps

private class ApacheBasedSimpleHttpClient(client: CloseableHttpAsyncClient)
  extends SimpleHttpClient[Task] {

  override def send(request: HttpClient.Request)
                   (implicit requestId: RequestId): Task[HttpClient.Response] = {
    val method = request.method match {
      case Method.Get => "GET"
      case Method.Post => "POST"
    }
    val httpRequest = SimpleHttpRequest.create(method, request.url.toStringRaw)
    request.headers.foreach { case (k, v) => httpRequest.setHeader(k, v) }

    Task.deferFuture {
      val promise = Promise[SimpleHttpResponse]()
      client.execute(
        httpRequest,
        new FutureCallback[SimpleHttpResponse] {
          override def completed(result: SimpleHttpResponse): Unit = promise.success(result)

          override def failed(ex: Exception): Unit = promise.failure(ex)

          override def cancelled(): Unit = promise.failure(new RuntimeException("HTTP request cancelled"))
        }
      )
      promise.future
    }.map { response =>
      // CLAUDE_MGW: getBodyText returns null for empty-body responses (unlike AHC's getResponseBody which returned "")
      HttpClient.Response(
        status = response.getCode,
        body = Option(response.getBodyText).getOrElse("")
      )
    }
  }

  override def close(): Task[Unit] =
    Task.delay(client.close())
}

private[factory] object ApacheBasedSimpleHttpClient extends RequestIdAwareLogging {

  def create(config: Config): HttpClient = new ApacheBasedSimpleHttpClient(
    newCloseableHttpAsyncClient(config)
  )

  private def newCloseableHttpAsyncClient(config: Config): CloseableHttpAsyncClient = {
    try {
      val connectionConfig =
        ConnectionConfig.custom()
          .setConnectTimeout(Timeout.ofMilliseconds(config.connectionTimeout.value.toMillis))
          .build()

      // CLAUDE_MGW: Use builder (instead of direct constructor) to support conditional TLS strategy injection
      val connManagerBuilder = PoolingAsyncClientConnectionManagerBuilder.create()
        .setDefaultConnectionConfig(connectionConfig)
        .setMaxConnTotal(config.connectionPoolSize.value)
        .setMaxConnPerRoute(config.connectionPoolSize.value)

      // CLAUDE_MGW: Migrate ssl bypass from old AHC client's setUseInsecureTrustManager(!config.validate) - this was silently lost in migration
      if (!config.validate) {
        val sslContext = SSLContextBuilder.create()
          .loadTrustMaterial(TrustAllStrategy.INSTANCE)
          .build()
        val tlsStrategy = ClientTlsStrategyBuilder.create()
          .setSslContext(sslContext)
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .buildAsync()
        connManagerBuilder.setTlsStrategy(tlsStrategy)
      }

      val connManager = connManagerBuilder.build()

      val requestConfig = RequestConfig.custom()
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
