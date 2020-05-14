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
package tech.beshu.ror.es.rradmin.rest

import java.util

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.rest._
import tech.beshu.ror.Constants
import tech.beshu.ror.adminapi._
import tech.beshu.ror.es.rradmin.{RRAdminActionType, RRAdminRequest, RRAdminResponse}
import collection.JavaConverters._
import RestRequest.Method._

@Inject
class RestRRAdminAction(controller: RestController)
  extends BaseRestHandler with RestHandler {

  override def routes() = List(
    new Route(POST, AdminRestApi.forceReloadRorPath.endpointString),
    new Route(GET, AdminRestApi.provideRorIndexConfigPath.endpointString),
    new Route(POST, AdminRestApi.updateIndexConfigurationPath.endpointString),
    new Route(GET, AdminRestApi.provideRorFileConfigPath.endpointString),
    new Route(GET, Constants.CURRENT_USER_METADATA_PATH)
  ).asJava

  override val getName: String = "ror-admin-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    client.execute(new RRAdminActionType, new RRAdminRequest(request), new RestToXContentListener[RRAdminResponse](channel))
  }
  
  private def register(method: String, path: String): Unit = {
    controller.registerHandler( this)
  }
}
