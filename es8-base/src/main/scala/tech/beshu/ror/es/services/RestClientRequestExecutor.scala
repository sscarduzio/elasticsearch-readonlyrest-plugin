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

import monix.eval.Task
import org.elasticsearch.client.{Request, Response, ResponseListener, RestClient}
import tech.beshu.ror.es.services.MultiNodeRestClient.RequestExecutor

import scala.concurrent.Promise

final class RestClientRequestExecutor(restClient: RestClient) extends RequestExecutor[Request, Response] {

  override def execute(request: Request): Task[Response] = Task.defer {
    val promise = Promise[Response]()
    restClient.performRequestAsync(
      request,
      new ResponseListener {
        override def onSuccess(response: Response): Unit = promise.success(response)

        override def onFailure(exception: Exception): Unit = promise.failure(exception)
      }
    )
    Task.fromFuture(promise.future)
  }

  override def close(): Unit = restClient.close()
}

object RestClientRequestExecutor {

  // one client configured with all hosts - the ES RestClient rotates over them itself
  def roundRobinClient(restClient: RestClient): MultiNodeRestClient[Request, Response] = {
    new DelegatingMultiNodeRestClient(new RestClientRequestExecutor(restClient))
  }

}
