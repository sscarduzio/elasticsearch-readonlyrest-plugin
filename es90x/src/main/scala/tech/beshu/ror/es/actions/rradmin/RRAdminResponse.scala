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
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.xcontent.{ToXContent, XContentBuilder}
import tech.beshu.ror.api.MainSettingsApi
import tech.beshu.ror.api.MainSettingsApi.MainSettingsResponse.*
import tech.beshu.ror.api.MainSettingsApi.*
import tech.beshu.ror.es.utils.StatusToXContentObject

class RRAdminResponse(response: MainSettingsApi.MainSettingsResponse)
  extends ActionResponse with StatusToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case forceReloadSettings: MainSettingsResponse.ForceReloadMainSettings => forceReloadSettings match {
        case ForceReloadMainSettings.Success(message) => addResponseJson(builder, response.status, message)
        case ForceReloadMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideIndexSettings: MainSettingsResponse.ProvideIndexMainSettings => provideIndexSettings match {
        case ProvideIndexMainSettings.MainSettings(rawSettings) => addResponseJson(builder, response.status, rawSettings)
        case ProvideIndexMainSettings.MainSettingsNotFound(message) => addResponseJson(builder, response.status, message)
        case ProvideIndexMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideFileSettings: MainSettingsResponse.ProvideFileMainSettings => provideFileSettings match {
        case ProvideFileMainSettings.MainSettings(rawSettings) => addResponseJson(builder, response.status, rawSettings)
        case ProvideFileMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case updateIndexSettings: MainSettingsResponse.UpdateIndexMainSettings => updateIndexSettings match {
        case UpdateIndexMainSettings.Success(message) => addResponseJson(builder, response.status, message)
        case UpdateIndexMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case failure: MainSettingsResponse.Failure => failure match {
        case Failure.BadRequest(message) => addResponseJson(builder, response.status, message)
      }
    }
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def status: RestStatus = {
    response match {
      case _: ForceReloadMainSettings => RestStatus.OK
      case _: ProvideIndexMainSettings => RestStatus.OK
      case _: ProvideFileMainSettings => RestStatus.OK
      case _: UpdateIndexMainSettings => RestStatus.OK
      case failure: Failure => failure match {
        case Failure.BadRequest(_) => RestStatus.BAD_REQUEST
      }
    }
  }

  private def addResponseJson(builder: XContentBuilder, status: String, message: String): Unit = {
    builder.startObject
    builder.field("status", status)
    builder.field("message", message)
    builder.endObject
  }
}
