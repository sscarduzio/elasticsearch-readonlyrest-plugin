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
package tech.beshu.ror.es.actions.rrauthmock

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.StatusToXContentObject
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.xcontent.{ToXContent, XContentBuilder}
import tech.beshu.ror.api.AuthMockApi
import tech.beshu.ror.es.utils.XContentBuilderOps.*


class RRAuthMockResponse(response: AuthMockApi.AuthMockResponse)
  extends ActionResponse with StatusToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    builder.json(response.body)
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def status(): RestStatus = RestStatus.fromCode(response.statusCode.code)

}
