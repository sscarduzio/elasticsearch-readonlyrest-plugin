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

import io.netty.util.HashedWheelTimer
import monix.eval.Task
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.netty.channel.DefaultChannelPool
import org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClientConfig}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient.Method
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.{Config, HttpClient}
import tech.beshu.ror.utils.RequestIdAwareLogging

import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.jdk.FutureConverters.CompletionStageOps
import scala.language.postfixOps

private class AsyncBasedSimpleHttpClient(asyncHttpClient: AsyncHttpClient) extends SimpleHttpClient[Task] {

  override def send(request: HttpClient.Request)
                   (implicit requestId: RequestId): Task[HttpClient.Response] = {
    val asyncRequestBase = request.method match {
      case Method.Get => asyncHttpClient.prepareGet(request.url.toStringRaw)
      case Method.Post => asyncHttpClient.preparePost(request.url.toStringRaw)
    }
    val asyncRequest = request.headers.foldLeft(asyncRequestBase) { (soFar, header) => soFar.setHeader(header._1, header._2) }.build()
    Task
      .deferFuture(asyncHttpClient.executeRequest(asyncRequest).toCompletableFuture.asScala)
      .map { response =>
        HttpClient.Response(
          status = response.getStatusCode,
          body = response.getResponseBody,
        )
      }
  }

  override def close(): Task[Unit] = {
    Task.delay(asyncHttpClient.close())
  }
}

private[factory] object AsyncBasedSimpleHttpClient extends RequestIdAwareLogging {

  def create(config: Config): HttpClient = new AsyncBasedSimpleHttpClient(newAsyncHttpClient(config))

  private def newAsyncHttpClient(config: Config) = {
    try {
      val timer = new HashedWheelTimer
      val maxIdleTimeout = 60.seconds
      val connectionTtl = -1.milliseconds
      val cleanerPeriod = -1.milliseconds
      val pool = new DefaultChannelPool(maxIdleTimeout.toJava, connectionTtl.toJava, DefaultChannelPool.PoolLeaseStrategy.FIFO, timer, cleanerPeriod.toJava)
      asyncHttpClient {
        new DefaultAsyncHttpClientConfig.Builder()
          .setNettyTimer(timer)
          .setChannelPool(pool)
          .setUseInsecureTrustManager(!config.validate)
          .build()
      }
    } catch {
      case ex: Throwable =>
        noRequestIdLogger.error(s"Failed to create AsyncHttpClient", ex)
        throw ex
    }
  }
}
