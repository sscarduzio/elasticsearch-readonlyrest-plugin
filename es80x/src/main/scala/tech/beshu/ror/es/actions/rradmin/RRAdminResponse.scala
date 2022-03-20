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
import org.elasticsearch.common.xcontent.StatusToXContentObject
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.xcontent.{ToXContent, XContentBuilder}
import tech.beshu.ror.api.ConfigApi
import tech.beshu.ror.api.ConfigApi.ConfigResponse._
import tech.beshu.ror.api.ConfigApi._

class RRAdminResponse(response: ConfigApi.ConfigResponse)
  extends ActionResponse with StatusToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case forceReloadConfig: ConfigResponse.ForceReloadConfig => forceReloadConfig match {
        case ForceReloadConfig.Success(message) => addResponseJson(builder, response.status, message)
        case ForceReloadConfig.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideIndexConfig: ConfigResponse.ProvideIndexConfig => provideIndexConfig match {
        case ProvideIndexConfig.Config(rawConfig) => addResponseJson(builder, response.status, rawConfig)
        case ProvideIndexConfig.ConfigNotFound(message) => addResponseJson(builder, response.status, message)
        case ProvideIndexConfig.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideFileConfig: ConfigResponse.ProvideFileConfig => provideFileConfig match {
        case ProvideFileConfig.Config(rawConfig) => addResponseJson(builder, response.status, rawConfig)
        case ProvideFileConfig.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case updateIndexConfig: ConfigResponse.UpdateIndexConfig => updateIndexConfig match {
        case UpdateIndexConfig.Success(message) => addResponseJson(builder, response.status, message)
        case UpdateIndexConfig.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case failure: ConfigResponse.Failure => failure match {
        case Failure.BadRequest(message) => addResponseJson(builder, response.status, message)
      }
    }
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def status(): RestStatus = {
    response match {
      case _: ForceReloadConfig => RestStatus.OK
      case _: ProvideIndexConfig => RestStatus.OK
      case _: ProvideFileConfig => RestStatus.OK
      case _: UpdateIndexConfig => RestStatus.OK
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
