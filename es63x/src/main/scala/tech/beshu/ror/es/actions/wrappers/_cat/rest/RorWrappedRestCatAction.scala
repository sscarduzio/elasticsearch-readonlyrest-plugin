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
package tech.beshu.ror.es.actions.wrappers._cat.rest

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.rest.action.RestActionListener
import org.elasticsearch.rest.action.cat.RestCatAction
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestHandler, RestRequest}
import tech.beshu.ror.es.actions.wrappers._cat.{RorWrappedCatActionType, RorWrappedCatRequest, RorWrappedCatResponse}


@Inject
class RorWrappedRestCatAction(settings: Settings, catAction: RestCatAction)
  extends BaseRestHandler(settings) with RestHandler {

  override val getName: String = catAction.getName

  override def prepareRequest(request: RestRequest, client: NodeClient): BaseRestHandler.RestChannelConsumer = {
    (channel: RestChannel) =>
      def sendResponse(): Unit = catAction.prepareRequest(request, client).accept(channel)

      client.execute(
        RorWrappedCatActionType.instance,
        new RorWrappedCatRequest(sendResponse),
        new RestActionListener[RorWrappedCatResponse](channel) {
          override def processResponse(response: RorWrappedCatResponse): Unit = sendResponse()
        }
      )
  }
}
