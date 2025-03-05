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
package tech.beshu.ror.es.actions.rrmetadata.rest

import java.util

import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.GET
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestHandler, RestRequest}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rrmetadata.{RRUserMetadataActionType, RRUserMetadataRequest, RRUserMetadataResponse}

import scala.jdk.CollectionConverters.*

class RestRRUserMetadataAction
  extends BaseRestHandler with RestHandler {

  override def routes(): util.List[Route] = List(
    new Route(GET, constants.CURRENT_USER_METADATA_PATH)
  ).asJava

  override val getName: String = "ror-user-metadata-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    client.execute(new RRUserMetadataActionType, new RRUserMetadataRequest, new RestToXContentListener[RRUserMetadataResponse](channel))
  }
}
