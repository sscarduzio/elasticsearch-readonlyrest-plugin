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

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContent, XContentBuilder}
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.api.TestSettingsApi
import tech.beshu.ror.api.TestSettingsApi.TestSettingsResponse.*

import java.time.ZoneOffset

class RRTestSettingsResponse(response: TestSettingsApi.TestSettingsResponse)
  extends ActionResponse with StatusToXContentObject {

  def this() = {
    this(null)
  }

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case provideSettingsResponse: ProvideTestSettings => provideSettingsResponse match {
        case res: ProvideTestSettings.CurrentTestSettings => currentSettingsJson(builder, res)
        case ProvideTestSettings.TestSettingsNotConfigured(message) => addResponseJson(builder, response.status, message)
        case res: ProvideTestSettings.TestSettingsInvalidated => invalidatedSettingsJson(builder, res)
      }
      case updateSettingsResponse: UpdateTestSettings => updateSettingsResponse match {
        case res: UpdateTestSettings.SuccessResponse => updateSettingsSuccessResponseJson(builder, res)
        case UpdateTestSettings.FailedResponse(message) => addResponseJson(builder, response.status, message)
      }
      case invalidateSettingsResponse: InvalidateTestSettings => invalidateSettingsResponse match {
        case InvalidateTestSettings.SuccessResponse(message) => addResponseJson(builder, response.status, message)
        case InvalidateTestSettings.FailedResponse(message) => addResponseJson(builder, response.status, message)
      }
      case provideUsersResponse: ProvideLocalUsers => provideUsersResponse match {
        case res: ProvideLocalUsers.SuccessResponse => provideLocalUsersJson(builder, res)
        case ProvideLocalUsers.TestSettingsNotConfigured(message) => addResponseJson(builder, response.status, message)
        case ProvideLocalUsers.TestSettingsInvalidated(message) => addResponseJson(builder, response.status, message)
      }
      case failure: Failure => failure match {
        case Failure.BadRequest(message) => addResponseJson(builder, response.status, message)
      }
    }
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def status: RestStatus = response match {
    case _: ProvideTestSettings => RestStatus.OK
    case _: UpdateTestSettings => RestStatus.OK
    case _: InvalidateTestSettings => RestStatus.OK
    case _: ProvideLocalUsers => RestStatus.OK
    case failure: Failure => failure match {
      case Failure.BadRequest(_) => RestStatus.BAD_REQUEST
    }
  }

  private def addResponseJson(builder: XContentBuilder, status: String, message: String): Unit = {
    builder.startObject
    builder.field("status", status)
    builder.field("message", message)
    builder.endObject
  }

  private def currentSettingsJson(builder: XContentBuilder, response: ProvideTestSettings.CurrentTestSettings): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("ttl", response.ttl.toString())
    builder.field("settings", response.settings.rawYaml)
    builder.field("valid_to", response.validTo.atOffset(ZoneOffset.UTC).toString)
    warningsJson(builder, response.warnings)
    builder.endObject
  }

  private def invalidatedSettingsJson(builder: XContentBuilder, response: ProvideTestSettings.TestSettingsInvalidated): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("message", response.message)
    builder.field("settings", response.settings.rawYaml)
    builder.field("ttl", response.ttl.toString())
    builder.endObject
  }

  private def updateSettingsSuccessResponseJson(builder: XContentBuilder, response: UpdateTestSettings.SuccessResponse): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("message", response.message)
    builder.field("valid_to", response.validTo.atOffset(ZoneOffset.UTC).toString)
    warningsJson(builder, response.warnings)
    builder.endObject
  }

  private def provideLocalUsersJson(builder: XContentBuilder, response: ProvideLocalUsers.SuccessResponse): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.startArray("users")
    response.users.foreach { user =>
      builder.value(user)
    }
    builder.endArray()
    builder.field("unknown_users", response.unknownUsers)
    builder.endObject
  }

  private def warningsJson(builder: XContentBuilder, warnings: List[Warning]): Unit = {
    builder.startArray("warnings")
    warnings.foreach { warning =>
      builder.startObject()
      builder.field("block_name", warning.blockName)
      builder.field("rule_name", warning.ruleName)
      builder.field("message", warning.message)
      builder.field("hint", warning.hint)
      builder.endObject()
    }
    builder.endArray()
  }
}
