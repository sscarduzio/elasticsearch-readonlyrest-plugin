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
package tech.beshu.ror.es.actions.rrtestconfig

import org.elasticsearch.action.{Action, ActionRequestBuilder}
import org.elasticsearch.client.ElasticsearchClient
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.api.TestConfigApi

class RRTestConfigActionType extends Action[RRTestConfigRequest, RRTestConfigResponse, RRTestConfigActionType.RequestBuilder](
  RRTestConfigActionType.name
) {

  override def newResponse(): RRTestConfigResponse = new RRTestConfigResponse()

  override def newRequestBuilder(client: ElasticsearchClient): RRTestConfigActionType.RequestBuilder =
    new RRTestConfigActionType.RequestBuilder(client, this, new RRTestConfigRequest())
}

object RRTestConfigActionType {
  class RequestBuilder(client: ElasticsearchClient, actionType: RRTestConfigActionType, request: RRTestConfigRequest)
    extends ActionRequestBuilder[RRTestConfigRequest, RRTestConfigResponse, RequestBuilder](client, actionType, request)

  val name = domain.Action.rorTestConfigAction.value
  val instance = new RRTestConfigActionType()
}

