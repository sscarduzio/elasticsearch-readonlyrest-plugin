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

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContent, XContentBuilder}
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.api.TestConfigApi
import tech.beshu.ror.api.TestConfigApi.TestConfigResponse._

import java.time.ZoneOffset

class RRTestConfigResponse(response: TestConfigApi.TestConfigResponse)
  extends ActionResponse with StatusToXContentObject {

  def this() = {
    this(null)
  }

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case provideConfigResponse: ProvideTestConfig => provideConfigResponse match {
        case res: ProvideTestConfig.CurrentTestSettings => currentConfigJson(builder, res)
        case ProvideTestConfig.TestSettingsNotConfigured(message) => addResponseJson(builder, response.status, message)
        case res: ProvideTestConfig.TestSettingsInvalidated => invalidatedConfigJson(builder, res)
      }
      case updateConfigResponse: UpdateTestConfig => updateConfigResponse match {
        case res: UpdateTestConfig.SuccessResponse => updateConfigSuccessResponseJson(builder, res)
        case UpdateTestConfig.FailedResponse(message) => addResponseJson(builder, response.status, message)
      }
      case invalidateConfigResponse: InvalidateTestConfig => invalidateConfigResponse match {
        case InvalidateTestConfig.SuccessResponse(message) => addResponseJson(builder, response.status, message)
        case InvalidateTestConfig.FailedResponse(message) => addResponseJson(builder, response.status, message)
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

  override def status(): RestStatus = response match {
    case _: ProvideTestConfig => RestStatus.OK
    case _: UpdateTestConfig => RestStatus.OK
    case _: InvalidateTestConfig => RestStatus.OK
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

  private def currentConfigJson(builder: XContentBuilder, response: ProvideTestConfig.CurrentTestSettings): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("ttl", response.ttl.toString())
    builder.field("settings", response.settings.raw)
    builder.field("valid_to", response.validTo.atOffset(ZoneOffset.UTC).toString)
    builder.startArray("warnings")
    response.warnings.foreach { warning =>
      builder.startObject()
      builder.field("block_name", warning.blockName)
      builder.field("rule_name", warning.ruleName)
      builder.field("message", warning.message)
      builder.field("hint", warning.hint)
      builder.endObject()
    }
    builder.endArray()
    builder.endObject
  }

  private def invalidatedConfigJson(builder: XContentBuilder, response: ProvideTestConfig.TestSettingsInvalidated): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("message", response.message)
    builder.field("settings", response.settings.raw)
    builder.field("ttl", response.ttl.toString())
    builder.endObject
  }

  private def updateConfigSuccessResponseJson(builder: XContentBuilder, response: UpdateTestConfig.SuccessResponse): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.field("message", response.message)
    builder.field("valid_to", response.validTo.atOffset(ZoneOffset.UTC).toString)
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
}
