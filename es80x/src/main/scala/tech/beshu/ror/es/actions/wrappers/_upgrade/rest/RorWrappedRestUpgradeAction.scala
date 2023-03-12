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
package tech.beshu.ror.es.actions.wrappers._upgrade.rest

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.rest.action.RestActionListener
import org.elasticsearch.rest.action.admin.indices.RestUpgradeActionDeprecated
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestHandler, RestRequest}
import tech.beshu.ror.es.actions.wrappers._upgrade.{RorWrappedUpgradeActionType, RorWrappedUpgradeRequest, RorWrappedUpgradeResponse}

import java.util

class RorWrappedRestUpgradeAction(upgradeAction: RestUpgradeActionDeprecated) extends BaseRestHandler {

  override val getName: String = upgradeAction.getName

  override def routes(): util.List[RestHandler.Route] = upgradeAction.routes()

  override def prepareRequest(request: RestRequest, client: NodeClient): BaseRestHandler.RestChannelConsumer = {
    (channel: RestChannel) =>
      def sendResponse(): Unit = upgradeAction.prepareRequest(request, client).accept(channel)

      client.execute(
        RorWrappedUpgradeActionType.instance,
        new RorWrappedUpgradeRequest(sendResponse),
        new RestActionListener[RorWrappedUpgradeResponse](channel) {
          override def processResponse(response: RorWrappedUpgradeResponse): Unit = sendResponse()
        }
      )
  }
}
