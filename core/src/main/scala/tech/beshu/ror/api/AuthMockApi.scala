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

import cats.data.EitherT
import cats.implicits._
import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, DecodingFailure}
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService => AuthenticationService, ExternalAuthorizationService => AuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.blocks.mocks.{MapsBasedMocksProvider, MocksProvider, MutableMocksProviderWithCachePerRequest}
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.api.AuthMockApi.AuthMockResponse.{Failure, ProvideAuthMock, UpdateAuthMock}
import tech.beshu.ror.api.AuthMockApi.AuthMockService._
import tech.beshu.ror.boot.RorInstance.TestEngineRorConfig
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.RorConfig
import tech.beshu.ror.utils.CirceOps.CirceErrorOps

import scala.language.postfixOps

class AuthMockApi(rorInstance: RorInstance,
                  mockProvider: MutableMocksProviderWithCachePerRequest)
  extends Logging {

  import AuthMockApi.Utils._
  import AuthMockApi.Utils.decoders._
  import AuthMockApi._

  def call(request: AuthMockRequest)
          (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val response = request.aType match {
      case AuthMockRequest.Type.ProvideAuthMock => provideAuthMock()
      case AuthMockRequest.Type.UpdateAuthMock => updateAuthMock(request.body)
    }
    response
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def provideAuthMock()
                             (implicit requestId: RequestId): Task[AuthMockResponse] = {
    withRorConfigAuthServices(
      action = readCurrentAuthMocks,
      onNotSet = AuthMockResponse.ProvideAuthMock.NotConfigured.apply
    )
      .map(_.merge)
  }

  private def readCurrentAuthMocks(services: RorConfig.Services)
                                  (implicit requestId: RequestId): AuthMockResponse.ProvideAuthMock.CurrentAuthMocks = {
    val ldaps = services.ldaps.map { serviceId =>
      toAuthMockService(serviceId, mockProvider.ldapServiceWith(serviceId))
    }
    val extAuthn = services.authenticationServices.map { serviceId =>
      toAuthMockService(serviceId, mockProvider.externalAuthenticationServiceWith(serviceId))
    }
    val extAuthz = services.authorizationServices.map { serviceId =>
      toAuthMockService(serviceId, mockProvider.externalAuthorizationServiceWith(serviceId))
    }
    AuthMockResponse.ProvideAuthMock.CurrentAuthMocks((ldaps ++ extAuthn ++ extAuthz).toList)
  }

  private def updateAuthMock(body: String)
                            (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val result = for {
      updateRequest <- EitherT.fromEither[Task](
        io.circe.parser.decode[UpdateMocksRequest](body)
          .left.map(error => AuthMockResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage}]"))
      )
      authServices <- readCurrentAuthServices()
      _ <- EitherT.fromEither[Task](validateProvidedMocks(updateRequest, authServices))
      result <- EitherT[Task, AuthMockResponse, AuthMockResponse] {
        Task.delay {
          mockProvider.update(mocksProvider = toDomain(updateRequest.services), ttl = None)
          Right(UpdateAuthMock.Success("Auth mock updated"))
        }
      }
    } yield result

    result.value.map(_.merge)
  }

  private def readCurrentAuthServices()
                                     (implicit requestId: RequestId): EitherT[Task, AuthMockResponse, RorConfig.Services] = {
    EitherT(withRorConfigAuthServices(
      action = identity,
      onNotSet = AuthMockResponse.UpdateAuthMock.NotConfigured.apply
    ))
  }

  private def withRorConfigAuthServices[A, B](action: RorConfig.Services => B,
                                              onNotSet: String => A)
                                             (implicit requestId: RequestId): Task[Either[A, B]] = {
    rorInstance.currentTestEngineRorConfig().map {
      case TestEngineRorConfig.NotSet =>
        Left(onNotSet("ROR Test settings are not configured. To use Auth Services Mock ROR has to have Test settings active."))
      case TestEngineRorConfig.Present(config) =>
        Right(action(config.services))
    }
  }

  private def validateProvidedMocks(updateRequest: UpdateMocksRequest,
                                    services: RorConfig.Services): Either[AuthMockResponse, Unit] = {
    updateRequest
      .services
      .map {
        case LdapAuthorizationService(name, _) =>
          services.ldaps.find(_.value === name).toValidNel(name)
        case ExternalAuthenticationService(name, _) =>
          services.authenticationServices.find(_.value === name).toValidNel(name)
        case ExternalAuthorizationService(name, _) =>
          services.authorizationServices.find(_.value === name).toValidNel(name)
      }
      .sequence
      .map(_ => ())
      .leftMap { unknownAuthServices =>
        val unknownServices = unknownAuthServices.mkString_("[", ",", "]")
        AuthMockResponse.UpdateAuthMock.UnknownAuthServicesDetected(
          s"ROR doesn't allow to configure unknown Auth Services. Only the ones used in ROR's Test settings can be configured. Unknown services: $unknownServices"
        )
      }
      .toEither
  }

  private implicit val eqNonEmptyString: Eq[NonEmptyString] = Eq.fromUniversalEquals
  private implicit val showNonEmptyString: Show[NonEmptyString] = Show.show(_.value)
}

object AuthMockApi {

  final case class AuthMockRequest(aType: AuthMockRequest.Type,
                                   body: String)
  object AuthMockRequest {
    sealed trait Type
    object Type {
      case object ProvideAuthMock extends Type
      case object UpdateAuthMock extends Type
    }
  }

  sealed trait AuthMockResponse
  object AuthMockResponse {
    sealed trait ProvideAuthMock extends AuthMockResponse
    object ProvideAuthMock {
      final case class CurrentAuthMocks(services: List[AuthMockService]) extends ProvideAuthMock
      final case class NotConfigured(message: String) extends ProvideAuthMock
    }

    sealed trait UpdateAuthMock extends AuthMockResponse
    object UpdateAuthMock {
      final case class Success(message: String) extends UpdateAuthMock
      final case class NotConfigured(message: String) extends UpdateAuthMock
      final case class UnknownAuthServicesDetected(message: String) extends UpdateAuthMock
    }

    sealed trait Failure extends AuthMockResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  sealed trait AuthMockService
  object AuthMockService {
    final case class MockUser(name: NonEmptyString)
    final case class MockUserWithGroups(name: NonEmptyString, groups: List[NonEmptyString])

    sealed trait MockMode[+T]
    object MockMode {
      case object NotConfigured extends MockMode[Nothing]
      final case class Enabled[T](configuredMock: T) extends MockMode[T]
    }

    final case class LdapAuthorizationService(name: NonEmptyString, mock: MockMode[LdapAuthorizationService.Mock]) extends AuthMockService
    object LdapAuthorizationService {
      final case class Mock(users: List[MockUserWithGroups])
    }

    final case class ExternalAuthenticationService(name: NonEmptyString, mock: MockMode[ExternalAuthenticationService.Mock]) extends AuthMockService
    object ExternalAuthenticationService {
      final case class Mock(users: List[MockUser])

    }

    final case class ExternalAuthorizationService(name: NonEmptyString, mock: MockMode[ExternalAuthorizationService.Mock]) extends AuthMockService
    object ExternalAuthorizationService {
      final case class Mock(users: List[MockUserWithGroups])
    }
  }

  implicit class StatusFromAuthMockResponse(val response: AuthMockResponse) extends AnyVal {
    def status: String = response match {
      case _: ProvideAuthMock.CurrentAuthMocks => "TEST_SETTINGS_PRESENT"
      case _: ProvideAuthMock.NotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: UpdateAuthMock.Success => "OK"
      case _: UpdateAuthMock.NotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: UpdateAuthMock.UnknownAuthServicesDetected => "UNKNOWN_AUTH_SERVICES_DETECTED"
      case _: Failure.BadRequest => "FAILED"
    }
  }

  implicit class AuthMockServiceOps(val service: AuthMockService) extends AnyVal {
    def serviceType: String = service match {
      case _: LdapAuthorizationService => "LDAP"
      case _: ExternalAuthenticationService => "EXT_AUTHN"
      case _: ExternalAuthorizationService => "EXT_AUTHZ"
    }
  }

  private object Utils {
    final case class UpdateMocksRequest(services: List[AuthMockService])

    implicit class MockUserOps(val mock: MockUserWithGroups) extends AnyVal {
      def domainUserId: User.Id = User.Id(mock.name)

      def domainGroups: Set[Group] = mock.groups.map(Group.apply).toSet
    }

    def toAuthMockService(serviceId: LdapService#Id,
                          maybeMock: Option[LdapServiceMock]): AuthMockService = {
      val mockMode =
        maybeMock
          .map {
            _.users
              .map(user => MockUserWithGroups(user.id.value, user.groups.map(_.value).toList))
              .toList
          }
          .map(AuthMockService.LdapAuthorizationService.Mock.apply)
          .map(MockMode.Enabled.apply)
          .getOrElse(MockMode.NotConfigured)

      AuthMockService.LdapAuthorizationService(name = serviceId.value, mock = mockMode)
    }

    def toAuthMockService(serviceId: AuthorizationService#Id,
                          maybeMock: Option[ExternalAuthorizationServiceMock]): AuthMockService = {
      val mockMode =
        maybeMock
          .map {
            _.users
              .map(user => MockUserWithGroups(user.id.value, user.groups.map(_.value).toList))
              .toList
          }
          .map(AuthMockService.ExternalAuthorizationService.Mock.apply)
          .map(MockMode.Enabled.apply)
          .getOrElse(MockMode.NotConfigured)

      ExternalAuthorizationService(name = serviceId.value, mock = mockMode)
    }

    def toAuthMockService(serviceId: AuthenticationService#Id,
                          maybeMock: Option[ExternalAuthenticationServiceMock]): AuthMockService = {
      val mockMode =
        maybeMock
          .map {
            _.users.map(user => MockUser(user.id.value)).toList
          }
          .map(AuthMockService.ExternalAuthenticationService.Mock.apply)
          .map(MockMode.Enabled.apply)
          .getOrElse(MockMode.NotConfigured)

      ExternalAuthenticationService(name = serviceId.value, mock = mockMode)
    }

    def toDomain(services: List[AuthMockService]): MapsBasedMocksProvider = {
      services.foldLeft(MapsBasedMocksProvider()) { (mocksProvider, service) =>
        service match {
          case LdapAuthorizationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case LdapAuthorizationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              ldapMocks = mocksProvider.ldapMocks +
                (LdapService.Name(name) -> LdapServiceMock(mock.users.map(toLdapMock).toSet))
            )
          case ExternalAuthenticationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case ExternalAuthenticationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              externalAuthenticationServiceMocks = mocksProvider.externalAuthenticationServiceMocks +
                (AuthenticationService.Name(name) -> ExternalAuthenticationServiceMock(users = mock.users.map(toAuthenticationMock).toSet))
            )
          case ExternalAuthorizationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case ExternalAuthorizationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              externalAuthorizationServiceMocks = mocksProvider.externalAuthorizationServiceMocks +
                (AuthorizationService.Name(name) -> ExternalAuthorizationServiceMock(users = mock.users.map(toAuthorizationMock).toSet))
            )
        }
      }
    }

    private def toLdapMock(user: MockUserWithGroups) = {
      MocksProvider.LdapServiceMock.LdapUserMock(id = user.domainUserId, groups = user.domainGroups)
    }

    private def toAuthorizationMock(user: MockUserWithGroups) = {
      MocksProvider.ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock(id = user.domainUserId, groups = user.domainGroups)
    }

    private def toAuthenticationMock(user: MockUser) = {
      MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock(id = User.Id(user.name))
    }

    object decoders {
      implicit val nonEmptyStringDecoder: Decoder[NonEmptyString] = Decoder.decodeString.emap(NonEmptyString.from)
      implicit val mockUserDecoder: Decoder[MockUser] = Decoder.forProduct1("name")(MockUser.apply)
      implicit val mockServiceUserDecoder: Decoder[MockUserWithGroups] =
        Decoder.forProduct2("name", "groups")(MockUserWithGroups.apply)

      private def mockModeDecoder[T: Decoder]: Decoder[MockMode[T]] = Decoder.instance { c =>
        c
          .as[String]
          .flatMap {
            case "NOT_CONFIGURED" => Right(MockMode.NotConfigured)
            case "" => Left(DecodingFailure(s"Mock type cannot be empty", ops = c.history))
            case other => Left(DecodingFailure(s"Unknown type of mock: $other", ops = c.history))
          }
          .orElse(Decoder[T].apply(c).map(MockMode.Enabled.apply))
      }

      implicit val ldapAuthorizationServiceDecoder: Decoder[LdapAuthorizationService] = {
        implicit val mockDecoder: Decoder[LdapAuthorizationService.Mock] =
          Decoder.forProduct1("users")(LdapAuthorizationService.Mock.apply)
        implicit val modeDecoder: Decoder[MockMode[LdapAuthorizationService.Mock]] =
          mockModeDecoder[LdapAuthorizationService.Mock]

        Decoder.forProduct2("name", "mock")(LdapAuthorizationService.apply)
      }

      implicit val externalAuthenticationMockType: Decoder[ExternalAuthenticationService] = {
        implicit val mockDecoder: Decoder[ExternalAuthenticationService.Mock] =
          Decoder.forProduct1("users")(ExternalAuthenticationService.Mock.apply)
        implicit val modeDecoder: Decoder[MockMode[ExternalAuthenticationService.Mock]] =
          mockModeDecoder[ExternalAuthenticationService.Mock]

        Decoder.forProduct2("name", "mock")(ExternalAuthenticationService.apply)

      }

      implicit val externalAuthorizationMockType: Decoder[ExternalAuthorizationService] = {
        implicit val mockDecoder: Decoder[ExternalAuthorizationService.Mock] =
          Decoder.forProduct1("users")(ExternalAuthorizationService.Mock.apply)
        implicit val modeDecoder: Decoder[MockMode[ExternalAuthorizationService.Mock]] =
          mockModeDecoder[ExternalAuthorizationService.Mock]

        Decoder.forProduct2("name", "mock")(ExternalAuthorizationService.apply)
      }

      implicit val authMockServiceDecoder: Decoder[AuthMockService] = Decoder.instance { c =>
        for {
          serviceType <- c.downField("type").as[String]
          service <- serviceType match {
            case "LDAP" => Decoder[LdapAuthorizationService].apply(c)
            case "EXT_AUTHN" => Decoder[ExternalAuthenticationService].apply(c)
            case "EXT_AUTHZ" => Decoder[ExternalAuthorizationService].apply(c)
            case other => Left(DecodingFailure(s"Unknown auth mock service type: $other", Nil))
          }
        } yield service
      }

      implicit val updateRequestDecoder: Decoder[UpdateMocksRequest] = Decoder.forProduct1("services")(UpdateMocksRequest.apply)
    }

  }
}