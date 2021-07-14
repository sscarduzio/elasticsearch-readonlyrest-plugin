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
package tech.beshu.ror.es.handler.request

import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.rest.{BytesRestResponse, RestChannel}
import org.elasticsearch.tasks.Task
import tech.beshu.ror.es.{RorRestChannel, TransportServiceInterceptor}

trait RequestContinuation {

  def proceed(request: ActionRequest,
              customListener: ActionListener[ActionResponse] => ActionListener[ActionResponse] = identity): Unit

  def fail(ex: Throwable): Unit

  def respondWith(response: ActionResponse): Unit

  def customResponse(createResponse: RestChannel => BytesRestResponse): Unit

}

class EsRequestContinuation(task: Task,
                            action: String,
                            baseListener: ActionListener[ActionResponse],
                            chain: ActionFilterChain[ActionRequest, ActionResponse],
                            channel: RorRestChannel)
  extends RequestContinuation {

  def proceed(request: ActionRequest,
              customListener: ActionListener[ActionResponse] => ActionListener[ActionResponse]): Unit = {
    chain.proceed(task, action, request, customListener(baseListener))
  }

  def fail(ex: Throwable): Unit = {
    baseListener.onFailure(new Exception(ex))
  }

  def respondWith(response: ActionResponse): Unit = {
    baseListener.onResponse(response)
  }

  def customResponse(createResponse: RestChannel => BytesRestResponse): Unit = {
    channel.sendResponse(createResponse(channel))
  }

}
