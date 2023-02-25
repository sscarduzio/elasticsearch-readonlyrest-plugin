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
package tech.beshu.ror.utils.httpclient

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{CloseableHttpResponse, HttpUriRequest}
import org.apache.http.client.utils.URIBuilder
import org.apache.http.config.SocketConfig
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.{HttpClientBuilder, HttpClients, StandardHttpRequestRetryHandler}
import org.apache.http.message.BasicHeader
import org.apache.http.ssl.SSLContextBuilder
import org.apache.http.util.EntityUtils
import org.apache.http.{Header, HttpResponse}

import java.net.URI
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

class RestClient(ssl: Boolean,
                 host: String,
                 port: Int,
                 basicAuth: Option[(String, String)],
                 headers: Header*) {

  def this(ssl: Boolean,
           host: String,
           port: Int) = {
    this(ssl, host, port, None)
  }

  private val underlying = createUnderlyingClient(
    headers ++ basicAuth.map { case (u, p) => createBasicAuthHeader(u, p) }
  )

  def from(path: String, queryParams: Map[String, String]): URI = buildUri(path, queryParams)

  def from(path: String): URI = buildUri(path, Map.empty)

  def execute(req: HttpUriRequest): CloseableHttpResponse = {
    Task(underlying.execute(req))
      .onErrorRestartLoop(0) {
        case (ex: HttpHostConnectException, 10, _) =>
          Task.raiseError(new Exception("Cannot connect to the host", ex))
        case (_: HttpHostConnectException, num, retry) =>
          retry(num + 1).delayExecution(1 second)
        case (ex, _, _) =>
          Task.raiseError(ex)
      }
      .runSyncUnsafe()
  }

  private def createUnderlyingClient(headers: Iterable[Header]) = {
    val timeout = readTimeout()
    val builder =
      if (ssl) withSsl(HttpClients.custom())
      else HttpClientBuilder.create()
    builder
      .setRetryHandler(new StandardHttpRequestRetryHandler(3, true))
      .setDefaultHeaders(headers.toSeq.asJava)
      .setDefaultSocketConfig(SocketConfig.custom().build())
      .setDefaultRequestConfig(
        RequestConfig.custom()
          .setConnectTimeout(timeout * 1000)
          .setConnectionRequestTimeout(timeout * 1000)
          .setSocketTimeout(timeout * 1000).build()
      )
      .build()
  }

  private def withSsl(builder: HttpClientBuilder): HttpClientBuilder = {
    val sslCtxBuilder = new SSLContextBuilder();
    sslCtxBuilder.loadTrustMaterial(null, new TrustAllCertificatesStrategy());
    val sslsf = new SSLConnectionSocketFactory(sslCtxBuilder.build(), NoopHostnameVerifier.INSTANCE);
    builder.setSSLSocketFactory(sslsf)
  }

  private def readTimeout() = {
    Try(Integer.parseInt(System.getProperty("rest_timeout")))
      .getOrElse(100)
  }

  private def createBasicAuthHeader(user: String, password: String) = {
    val authenticate = BasicScheme.authenticate(new UsernamePasswordCredentials(user, password), "UTF-8", false)
    new BasicHeader("Authorization", authenticate.getValue)
  }

  private def buildUri(path: String, queryParams: Map[String, String]) = {
    val uriBuilder = new URIBuilder()
      .setScheme(if (ssl) "https" else "http")
      .setHost(host)
      .setPort(port)
      .setPath(("/" + path + "/").replaceAll("//", "/"))
    queryParams
      .foldLeft(uriBuilder) {
        case (builder, (name, value)) => builder.setParameter(name, value)
      }
      .build()
  }
}

object RestClient {

  def bodyFrom(response: HttpResponse): String = {
    EntityUtils.toString(response.getEntity)
  }
}