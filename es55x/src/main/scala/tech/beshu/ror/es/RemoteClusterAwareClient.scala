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

import org.elasticsearch.action._
import org.elasticsearch.client.support.AbstractClient
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.{TransportRequestOptions, TransportService}

final class RemoteClusterAwareClient(settings: Settings,
                                     threadPool: ThreadPool,
                                     service: TransportService,
                                     clusterAlias: String)
  extends AbstractClient(settings, threadPool) {

  override def doExecute[Request <: ActionRequest, Response <: ActionResponse, RequestBuilder <: ActionRequestBuilder[Request, Response, RequestBuilder]](action: Action[Request, Response, RequestBuilder],
                                                                                                                                                          request: Request,
                                                                                                                                                          listener: ActionListener[Response]): Unit = {
    service
      .getRemoteClusterService
      .ensureConnected(
        clusterAlias,
        new ActionListener[Void] {
          override def onResponse(response: Void): Unit = {
            service
              .sendRequest(
                service.getRemoteClusterService.getConnection(clusterAlias),
                action.name(),
                request,
                TransportRequestOptions.EMPTY,
                new ActionListenerResponseHandler(listener, () => action.newResponse())
              )
          }

          override def onFailure(e: Exception): Unit = listener.onFailure(e)
        }
      )
  }

  override def close(): Unit = {}
}
