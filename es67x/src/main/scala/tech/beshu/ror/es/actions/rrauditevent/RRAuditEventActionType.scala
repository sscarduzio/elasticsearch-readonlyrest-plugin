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
package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.{Action, ActionRequestBuilder}
import org.elasticsearch.client.ElasticsearchClient
import tech.beshu.ror.accesscontrol.domain.Action.RorAction

class RRAuditEventActionType
  extends Action[RRAuditEventRequest, RRAuditEventResponse, RRAuditEventActionType.RequestBuilder](RRAuditEventActionType.name) {

  override def newResponse(): RRAuditEventResponse = new RRAuditEventResponse()

  override def newRequestBuilder(client: ElasticsearchClient): RRAuditEventActionType.RequestBuilder =
    new RRAuditEventActionType.RequestBuilder(client, this, new RRAuditEventRequest())
}

object RRAuditEventActionType {
  class RequestBuilder(client: ElasticsearchClient, actionType: RRAuditEventActionType, request: RRAuditEventRequest)
    extends ActionRequestBuilder[RRAuditEventRequest, RRAuditEventResponse, RequestBuilder](client, actionType, request)

  val name: String = RorAction.RorAuditEventAction.value
  val instance = new RRAuditEventActionType()
}