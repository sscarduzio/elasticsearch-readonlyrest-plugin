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

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.*
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rrtestconfig.{RRTestConfigActionType, RRTestConfigRequest, RRTestConfigResponse}
import tech.beshu.ror.es.utils.RestToXContentWithStatusListener

@Inject
class RestRRTestConfigAction(controller: RestController)
  extends BaseRestHandler with RestHandler {

  register("GET", constants.PROVIDE_TEST_CONFIG_PATH)
  register("POST", constants.UPDATE_TEST_CONFIG_PATH)
  register("DELETE", constants.DELETE_TEST_CONFIG_PATH)
  register("GET", constants.PROVIDE_LOCAL_USERS_PATH)

  override val getName: String = "ror-test-config-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = new RestChannelConsumer {
    private val rorTestConfigRequest = RRTestConfigRequest.createFrom(request)

    override def accept(channel: RestChannel): Unit = {
      client.execute(new RRTestConfigActionType, rorTestConfigRequest, new RestToXContentWithStatusListener[RRTestConfigResponse](channel))
    }
  }

  private def register(method: String, path: String): Unit =
    controller.registerHandler(RestRequest.Method.valueOf(method), path, this)
}
