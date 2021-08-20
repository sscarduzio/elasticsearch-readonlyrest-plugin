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
package tech.beshu.ror.es.actions.rrauditevent.rest

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.action.RestBuilderListener
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestResponse, RestStatus}
import tech.beshu.ror.es.actions.rrauditevent.RRAuditEventResponse

class RestRRAuditEventActionResponseBuilder(channel: RestChannel)
  extends RestBuilderListener[RRAuditEventResponse](channel) {

  override def buildResponse(response: RRAuditEventResponse, builder: XContentBuilder): RestResponse = {
    new BytesRestResponse(RestStatus.NO_CONTENT, "")
  }
}
