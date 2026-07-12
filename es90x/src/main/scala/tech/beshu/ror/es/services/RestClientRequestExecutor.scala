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
package tech.beshu.ror.es.services

import cats.data.NonEmptyList
import org.elasticsearch.client.{Request, Response, ResponseException, ResponseListener, RestClient}
import tech.beshu.ror.es.services.MultiNodeRestClient.{FailoverDecision, RequestExecutor, ResponseHandler}
import tech.beshu.ror.es.utils.RestResponseOps.*

import java.io.IOException
import java.time.Clock

final class RestClientRequestExecutor(restClient: RestClient) extends RequestExecutor[Request, Response] {

  override def execute(request: Request, responseHandler: ResponseHandler[Response]): Unit = {
    restClient.performRequestAsync(
      request,
      new ResponseListener {
        override def onSuccess(response: Response): Unit = responseHandler.onSuccess(response)

        override def onFailure(exception: Exception): Unit = responseHandler.onFailure(exception)
      }
    )
  }

  override def close(): Unit = restClient.close()
}

object RestClientRequestExecutor {

  // one client configured with all hosts - the ES RestClient rotates over them itself
  def roundRobinClient(restClient: RestClient): MultiNodeRestClient[Request, Response] = {
    new RoundRobinClient(new RestClientRequestExecutor(restClient))
  }

  // one client per host - FailoverClient decides which node to try and when
  def failoverClient(restClientPerNode: NonEmptyList[RestClient])(
      using clock: Clock
  ): MultiNodeRestClient[Request, Response] = {
    FailoverClient.create(
      nodeExecutors = restClientPerNode.map(new RestClientRequestExecutor(_)),
      failoverDecision = failoverDecision,
      clock = clock
    )
  }

  private val failoverDecision: Exception => FailoverDecision = {
    case exception: ResponseException if exception.getResponse.isRetryable =>
      FailoverDecision.TryNextNode
    case _: ResponseException =>
      FailoverDecision.Stop
    case _: IOException =>
      FailoverDecision.TryNextNode
    case _ =>
      FailoverDecision.Stop
  }

}
