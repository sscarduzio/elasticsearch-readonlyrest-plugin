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
package tech.beshu.ror.es.actions.rrtestsettings

import org.elasticsearch.action.{Action, ActionRequestBuilder}
import org.elasticsearch.client.ElasticsearchClient
import tech.beshu.ror.accesscontrol.domain.Action.RorAction

class RRTestSettingsActionType extends Action[RRTestSettingsRequest, RRTestSettingsResponse, RRTestSettingsActionType.RequestBuilder](
  RRTestSettingsActionType.name
) {

  override def newResponse(): RRTestSettingsResponse = new RRTestSettingsResponse()

  override def newRequestBuilder(client: ElasticsearchClient): RRTestSettingsActionType.RequestBuilder =
    new RRTestSettingsActionType.RequestBuilder(client, this, new RRTestSettingsRequest())
}

object RRTestSettingsActionType {
  class RequestBuilder(client: ElasticsearchClient, actionType: RRTestSettingsActionType, request: RRTestSettingsRequest)
    extends ActionRequestBuilder[RRTestSettingsRequest, RRTestSettingsResponse, RequestBuilder](client, actionType, request)

  val name: String = RorAction.RorTestSettingsAction.value
  val instance = new RRTestSettingsActionType()
}