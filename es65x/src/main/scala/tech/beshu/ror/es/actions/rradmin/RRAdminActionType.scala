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
package tech.beshu.ror.es.actions.rradmin

import tech.beshu.ror.accesscontrol.domain
import org.elasticsearch.action.Action
import org.elasticsearch.client.ElasticsearchClient
import tech.beshu.ror.adminapi.AdminRestApi

class RRAdminActionType extends Action[RRAdminRequest, RRAdminResponse, RRAdminRequestBuilder](RRAdminActionType.name) {
  override def newRequestBuilder(client: ElasticsearchClient): RRAdminRequestBuilder =
    new RRAdminRequestBuilder(client, RRAdminActionType.instance)

  override def newResponse(): RRAdminResponse = {
    new RRAdminResponse(AdminRestApi.AdminResponse.notAvailable)
  }
}
object RRAdminActionType {
  val name = domain.Action.rorOldConfigAction.value
  val instance = new RRAdminActionType()
}