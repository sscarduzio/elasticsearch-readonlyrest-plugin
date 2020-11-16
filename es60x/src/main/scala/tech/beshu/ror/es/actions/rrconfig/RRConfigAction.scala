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
package tech.beshu.ror.es.actions.rrconfig

import org.elasticsearch.action.{Action, ActionRequestBuilder}
import org.elasticsearch.client.ElasticsearchClient
import org.elasticsearch.common.io.stream.Writeable

class RRConfigAction extends Action[RRConfigsRequest, RRConfigsResponse, RRConfigAction.RequestBuilder](RRConfigAction.name) {
  override def newResponse(): RRConfigsResponse = new RRConfigsResponse

  override def newRequestBuilder(client: ElasticsearchClient): RRConfigAction.RequestBuilder =
    new RRConfigAction.RequestBuilder(client, this, new RRConfigsRequest())
}

object RRConfigAction {
  class RequestBuilder(client: ElasticsearchClient, rRConfigAction: RRConfigAction, request: RRConfigsRequest)
    extends ActionRequestBuilder[RRConfigsRequest, RRConfigsResponse, RequestBuilder](client, rRConfigAction, request)
  val name = "cluster:ror/config/manage"
  val instance = new RRConfigAction
  val reader: Writeable.Reader[RRConfigsResponse] = RRConfigsResponseReader
}
