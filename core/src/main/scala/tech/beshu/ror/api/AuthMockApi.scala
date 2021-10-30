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

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.parser._
import io.circe.refined._
import io.circe.{Decoder, KeyDecoder}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.LdapServiceMock.LdapUserMock
import tech.beshu.ror.accesscontrol.blocks.mocks.{MapsBasedMocksProvider, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.boot.RorSchedulers

// todo: what about TTL?
class AuthMockApi(mockProvider: MutableMocksProviderWithCachePerRequest)
  extends Logging {

  import AuthMockApi._
  import AuthMockApi.coders._

  def call(request: AuthMockRequest)
          (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val apiCallResult = request.aType match {
      case AuthMockRequest.Type.UpdateAuthMock => updateAuthMock(request.body)
      case AuthMockRequest.Type.InvalidateAuthMock => invalidateAuthMock()
    }
    apiCallResult
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateAuthMock(body: String)
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
            mockProvider.update(mapsBasedMocksProvider)
            Success("ok") //todo:
          }
        case Left(error) =>
          Task.now(Failure("todo")) // todo:
      }
      .onErrorRecover { case ex =>
        logger.error(s"[${requestId.show}] Updating auth mock failed", ex)
        Failure("ex") //todo:
      }
  }

  private def invalidateAuthMock()
                                (implicit requestId: RequestId): Task[AuthMockResponse] = {
    Task
      .delay(mockProvider.invalidate())
      .map(_ => AuthMockApi.Success("Auth mock settings invalidated"))
  }
}

object AuthMockApi {

  final case class AuthMockRequest(aType: AuthMockRequest.Type,
                                   body: String)
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
    val mocksDecoder: Decoder[MapsBasedMocksProvider] = Decoder.forProduct1("ldaps")(ldaps =>
      new MapsBasedMocksProvider(ldaps)
    )

    private implicit lazy val ldapServiceIdDecoder: KeyDecoder[LdapService.Name] =
      KeyDecoder[NonEmptyString].map(LdapService.Name.apply)

    private implicit lazy val ldapServiceMockDecoder: Decoder[LdapServiceMock] =
      Decoder.forProduct1("users")(LdapServiceMock.apply)

    private implicit lazy val ldapUserMockDecoder: Decoder[LdapUserMock] =
      Decoder.forProduct2("name", "groups")(LdapUserMock.apply)

    private implicit lazy val userIdDecoder: Decoder[User.Id] =
      Decoder[NonEmptyString].map(User.Id.apply)

    private implicit lazy val groupDecoder: Decoder[Group] =
      Decoder[NonEmptyString].map(Group.apply)
  }
}