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

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContent, XContentBuilder}
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.api.MainSettingsApi
import tech.beshu.ror.api.MainSettingsApi.MainSettingsResponse.*
import tech.beshu.ror.api.MainSettingsApi.*
import tech.beshu.ror.es.utils.EsJsonBuilder

class RRAdminResponse(response: MainSettingsApi.MainSettingsResponse)
  extends ActionResponse with StatusToXContentObject {

  def this() = {
    this(null)
  }

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    val esJsonBuilder = new EsJsonBuilder(builder)
    buildResponse(esJsonBuilder, response)
    builder
  }

  override def status(): RestStatus = {
    response match {
      case _: ForceReloadMainSettings => RestStatus.OK
      case _: ProvideIndexMainSettings => RestStatus.OK
      case _: ProvideFileMainSettings => RestStatus.OK
      case _: ProvideAuditSettings => RestStatus.OK
      case _: UpdateIndexMainSettings => RestStatus.OK
      case failure: Failure => failure match {
        case Failure.BadRequest(_) => RestStatus.BAD_REQUEST
      }
    }
  }
}
