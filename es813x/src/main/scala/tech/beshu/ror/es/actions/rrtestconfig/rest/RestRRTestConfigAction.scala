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
package tech.beshu.ror.es.actions.rrtestconfig.rest

import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.{DELETE, GET, POST}
import org.elasticsearch.rest.*
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rrtestconfig.{RRTestConfigActionType, RRTestConfigRequest, RRTestConfigResponse}
import tech.beshu.ror.es.utils.RestToXContentWithStatusListener

import java.util
import scala.jdk.CollectionConverters.*

@Inject
class RestRRTestConfigAction()
  extends BaseRestHandler with RestHandler {

  override def routes(): util.List[Route] = List(
    new Route(GET, constants.PROVIDE_TEST_CONFIG_PATH),
    new Route(POST, constants.UPDATE_TEST_CONFIG_PATH),
    new Route(DELETE, constants.DELETE_TEST_CONFIG_PATH),
    new Route(GET, constants.PROVIDE_LOCAL_USERS_PATH)
  ).asJava

  override val getName: String = "ror-test-config-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = new RestChannelConsumer {
    private val rorTestConfigRequest = RRTestConfigRequest.createFrom(request)

    override def accept(channel: RestChannel): Unit = {
      client.execute(new RRTestConfigActionType, rorTestConfigRequest, new RestToXContentWithStatusListener[RRTestConfigResponse](channel))
    }
  }
}
