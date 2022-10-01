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
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContent, XContentBuilder}
import org.elasticsearch.rest.RestStatus
import tech.beshu.ror.api.AuthMockApi
import tech.beshu.ror.api.AuthMockApi.AuthMockResponse.{Failure, ProvideAuthMock, UpdateAuthMock}
import tech.beshu.ror.api.AuthMockApi.AuthMockService.{ExternalAuthenticationService, ExternalAuthorizationService, LdapAuthorizationService, MockMode}
import tech.beshu.ror.api.AuthMockApi.{AuthMockResponse, AuthMockService}

import scala.collection.JavaConverters._

class RRAuthMockResponse(response: AuthMockApi.AuthMockResponse)
  extends ActionResponse with StatusToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response match {
      case mock: AuthMockResponse.ProvideAuthMock => mock match {
        case ProvideAuthMock.CurrentAuthMocks(services) => currentServicesJson(builder, services)
        case ProvideAuthMock.NotConfigured(message) => addResponseJson(builder, response.status, message)
        case ProvideAuthMock.Invalidated(message) => addResponseJson(builder, response.status, message)
      }
      case mock: AuthMockResponse.UpdateAuthMock => mock match {
        case UpdateAuthMock.Success(message) => addResponseJson(builder, response.status, message)
        case UpdateAuthMock.NotConfigured(message) => addResponseJson(builder, response.status, message)
        case UpdateAuthMock.Invalidated(message) => addResponseJson(builder, response.status, message)
        case UpdateAuthMock.UnknownAuthServicesDetected(message) => addResponseJson(builder, response.status, message)
        case UpdateAuthMock.Failed(message) => addResponseJson(builder, response.status, message)
      }
      case failure: Failure => failure match {
        case Failure.BadRequest(message) => addResponseJson(builder, response.status, message)
      }
    }
    builder
  }

  override def writeTo(out: StreamOutput): Unit = ()

  override def status(): RestStatus = response match {
    case _: AuthMockResponse.ProvideAuthMock => RestStatus.OK
    case _: AuthMockResponse.UpdateAuthMock => RestStatus.OK
    case failure: AuthMockResponse.Failure => failure match {
      case Failure.BadRequest(_) => RestStatus.BAD_REQUEST
    }
  }

  private def addResponseJson(builder: XContentBuilder, status: String, message: String): Unit = {
    builder.startObject
    builder.field("status", status)
    builder.field("message", message)
    builder.endObject
  }

  private def currentServicesJson(builder: XContentBuilder, services: List[AuthMockService]): Unit = {
    builder.startObject
    builder.field("status", response.status)
    builder.startArray("services")
    services.foreach { service =>
      buildForService(builder, service)
    }
    builder.endArray()
    builder.endObject
  }

  private def buildForService(builder: XContentBuilder, service: AuthMockService): Unit = {
    service match {
      case AuthMockService.LdapAuthorizationService(name, mock) =>
        builder.startObject()
        builder.field("type", service.serviceType)
        builder.field("name", name.value)
        ldapMock(builder, mock)
        builder.endObject()
      case AuthMockService.ExternalAuthenticationService(name, mock) =>
        builder.startObject()
        builder.field("type", service.serviceType)
        builder.field("name", name.value)
        externalAuthenticationMock(builder, mock)
        builder.endObject()
      case AuthMockService.ExternalAuthorizationService(name, mock) =>
        builder.startObject()
        builder.field("type", service.serviceType)
        builder.field("name", name.value)
        externalAuthorizationMock(builder, mock)
        builder.endObject()
    }
  }

  private def externalAuthorizationMock(builder: XContentBuilder, mock: MockMode[ExternalAuthorizationService.Mock]): Unit = mock match {
    case MockMode.Enabled(configuredMock) =>
      builder.startObject("mock")
      builder.startArray("users")
      configuredMock.users.foreach { user =>
        builder.startObject()
        builder.field("name", user.name.value)
        builder.field("groups", user.groups.map(_.value).asJava)
        builder.endObject()
      }
      builder.endArray()
      builder.endObject()
    case MockMode.NotConfigured =>
      builder.field("mock", "NOT_CONFIGURED")
  }

  private def externalAuthenticationMock(builder: XContentBuilder, mock: MockMode[ExternalAuthenticationService.Mock]): Unit = mock match {
    case MockMode.Enabled(configuredMock) =>
      builder.startObject("mock")
      builder.startArray("users")
      configuredMock.users.foreach { user =>
        builder.startObject()
        builder.field("name", user.name.value)
        builder.endObject()
      }
      builder.endArray()
      builder.endObject()
    case MockMode.NotConfigured =>
      builder.field("mock", "NOT_CONFIGURED")
  }

  private def ldapMock(builder: XContentBuilder, mock: MockMode[LdapAuthorizationService.Mock]): Unit = mock match {
    case MockMode.Enabled(configuredMock) =>
      builder.startObject("mock")
      builder.startArray("users")
      configuredMock.users.foreach { user =>
        builder.startObject()
        builder.field("name", user.name.value)
        builder.field("groups", user.groups.map(_.value).asJava)
        builder.endObject()
      }
      builder.endArray()
      builder.endObject()
    case MockMode.NotConfigured =>
      builder.field("mock", "NOT_CONFIGURED")
  }
}
