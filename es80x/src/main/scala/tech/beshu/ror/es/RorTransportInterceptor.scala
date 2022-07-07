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
package tech.beshu.ror.es

import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.transport._
import tech.beshu.ror.accesscontrol.domain.Action
import tech.beshu.ror.es.utils.ThreadContextOps._

class RorTransportInterceptor(threadContext: ThreadContext, nodeName: String)
  extends TransportInterceptor {

  override def interceptSender(sender: TransportInterceptor.AsyncSender): TransportInterceptor.AsyncSender =
    new TransportInterceptor.AsyncSender {
      override def sendRequest[T <: TransportResponse](connection: Transport.Connection,
                                                       action: String,
                                                       request: TransportRequest,
                                                       options: TransportRequestOptions,
                                                       handler: TransportResponseHandler[T]): Unit = {
        if(Action.isInternal(action)) {
          threadContext.addSystemAuthenticationHeader(nodeName)
        }
        sender.sendRequest(connection, action, request, options, handler)
      }
    }
}