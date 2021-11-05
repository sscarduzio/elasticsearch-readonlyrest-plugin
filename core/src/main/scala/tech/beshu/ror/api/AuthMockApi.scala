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
package tech.beshu.ror.api

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.parser._
import io.circe.refined._
import io.circe.{Decoder, DecodingFailure, KeyDecoder, ParsingFailure}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.blocks.mocks.{MapsBasedMocksProvider, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.HttpOps
import tech.beshu.ror.{Constants, RequestId}

import scala.concurrent.duration._
import scala.language.postfixOps

class AuthMockApi(mockProvider: MutableMocksProviderWithCachePerRequest)
  extends Logging {

  import AuthMockApi._
  import AuthMockApi.coders._

  def call(request: AuthMockRequest)
          (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val apiCallResult = request.aType match {
      case AuthMockRequest.Type.UpdateAuthMock => updateAuthMock(request.body, authMockTtlFrom(request.headers))
      case AuthMockRequest.Type.InvalidateAuthMock => invalidateAuthMock()
    }
    apiCallResult
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateAuthMock(body: String,
                             authMockTtl: Option[FiniteDuration])
                            (implicit requestId: RequestId): Task[AuthMockResponse] = {
    Task
      .delay {
        for {
          json <- parse(body)
          mocks <- mocksDecoder.decodeJson(json)
        } yield mocks
      }
      .flatMap {
        case Right(mapsBasedMocksProvider) =>
          Task.delay {
            mockProvider.update(mapsBasedMocksProvider, authMockTtl.orElse(Some(defaults.authMockDefaultTtl)))
            Success("Auth mock updated")
          }
        case Left(error) =>
          Task.now(Failure {
            error match {
              case ParsingFailure(_, _) => "Cannot parse JSON"
              case _: DecodingFailure => "Invalid structure of sent JSON"
            }
          })
      }
      .onErrorRecover { case ex =>
        logger.error(s"[${requestId.show}] Updating auth mock failed", ex)
        AuthMockResponse.internalError
      }
  }

  private def invalidateAuthMock()
                                (implicit requestId: RequestId): Task[AuthMockResponse] = {
    Task
      .delay(mockProvider.invalidate())
      .map(_ => AuthMockApi.Success("Auth mock invalidated"))
  }

  private def authMockTtlFrom(headers: Map[String, NonEmptyList[String]]) = {
    HttpOps.finiteDurationHeaderValueFrom(headers, Constants.HEADER_AUTH_MOCK_TTL)
  }
}

object AuthMockApi {

  final case class AuthMockRequest(aType: AuthMockRequest.Type,
                                   body: String,
                                   headers: Map[String, NonEmptyList[String]])
  object AuthMockRequest {
    sealed trait Type
    object Type {
      case object UpdateAuthMock extends Type
      case object InvalidateAuthMock extends Type
    }
  }

  sealed trait AuthMockResponse
  object AuthMockResponse {
    def internalError: AuthMockResponse = Failure("Internal error")

    def notAvailable: AuthMockResponse = Failure("Service not available")
  }
  final case class Success(message: String) extends AuthMockResponse
  final case class Failure(message: String) extends AuthMockResponse

  private object coders {
    val mocksDecoder: Decoder[MapsBasedMocksProvider] = Decoder.instance { c =>
      val maps = for {
        ldaps <- c.downField("ldaps").as[Option[Map[LdapService.Name, LdapServiceMock]]]
        authnServices <- c.downField("authn_services").as[Option[Map[ExternalAuthenticationService.Name, ExternalAuthenticationServiceMock]]]
        authzServices <- c.downField("authz_services").as[Option[Map[ExternalAuthorizationService.Name, ExternalAuthorizationServiceMock]]]
      } yield (ldaps, authnServices, authzServices)
      maps.flatMap {
        case (None, None, None) =>
          Left(DecodingFailure("", List.empty))
        case (ldaps, authnServices, authzServices) =>
          Right(MapsBasedMocksProvider(
            ldaps.getOrElse(Map.empty),
            authnServices.getOrElse(Map.empty),
            authzServices.getOrElse(Map.empty)
          ))
      }
    }

    // ldaps
    private implicit lazy val ldapServiceIdDecoder: KeyDecoder[LdapService.Name] =
      KeyDecoder[NonEmptyString].map(LdapService.Name.apply)

    private implicit lazy val ldapServiceMockDecoder: Decoder[LdapServiceMock] =
      Decoder.forProduct1("users")(LdapServiceMock.apply)

    private implicit lazy val ldapUserMockDecoder: Decoder[LdapUserMock] =
      Decoder.forProduct2("name", "groups")(LdapUserMock.apply)

    // authn services
    private implicit lazy val authnServiceIdDecoder: KeyDecoder[ExternalAuthenticationService.Name] =
      KeyDecoder[String].map(ExternalAuthenticationService.Name.apply)

    private implicit lazy val externalAuthenticationServiceMockDecoder: Decoder[ExternalAuthenticationServiceMock] =
      Decoder.forProduct1("users")(ExternalAuthenticationServiceMock.apply)

    private implicit lazy val externalAuthenticationUserMockDecoder: Decoder[ExternalAuthenticationUserMock] =
      Decoder.forProduct1("name")(ExternalAuthenticationUserMock.apply)

    // authz services
    private implicit lazy val authzServiceIdDecoder: KeyDecoder[ExternalAuthorizationService.Name] =
      KeyDecoder[String].map(ExternalAuthorizationService.Name.apply)

    private implicit lazy val externalAuthorizationServiceMockDecoder: Decoder[ExternalAuthorizationServiceMock] =
      Decoder.forProduct1("users")(ExternalAuthorizationServiceMock.apply)

    private implicit lazy val externalAuthorizationServiceUserMockDecoder: Decoder[ExternalAuthorizationServiceUserMock] =
      Decoder.forProduct2("name", "groups")(ExternalAuthorizationServiceUserMock.apply)

    // commons
    private implicit lazy val userIdDecoder: Decoder[User.Id] =
      Decoder[NonEmptyString].map(User.Id.apply)

    private implicit lazy val groupDecoder: Decoder[Group] =
      Decoder[NonEmptyString].map(Group.apply)

  }

  object defaults {
    val authMockDefaultTtl: FiniteDuration = 30 minutes
  }
}