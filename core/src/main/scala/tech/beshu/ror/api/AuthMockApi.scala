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
import cats.implicits.*
import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.*
import io.circe.syntax.*
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService as AuthenticationService, ExternalAuthorizationService as AuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider.{ExternalAuthenticationServiceMock, ExternalAuthorizationServiceMock, LdapServiceMock}
import tech.beshu.ror.accesscontrol.blocks.mocks.{AuthServicesMocks, MocksProvider}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, GroupName, RequestId, User}
import tech.beshu.ror.accesscontrol.factory.RorDependencies
import tech.beshu.ror.boot.RorInstance.{IndexSettingsUpdateError, TestSettings}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.CirceOps.CirceErrorOps

class AuthMockApi(rorInstance: RorInstance)
  extends Logging {

  import AuthMockApi.*
  import AuthMockApi.AuthMockResponse.*
  import AuthMockApi.AuthMockService.*
  import AuthMockApi.Utils.*
  import AuthMockApi.Utils.codecs.*

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
      onNotSet = AuthMockResponse.ProvideAuthMock.NotConfigured.apply,
      onInvalidated = AuthMockResponse.ProvideAuthMock.Invalidated.apply
    )
      .map(_.merge)
  }

  private def readCurrentAuthMocks(services: RorDependencies.Services)
                                  (implicit requestId: RequestId): AuthMockResponse.ProvideAuthMock.CurrentAuthMocks = {
    val ldaps = services.ldaps.map { serviceId =>
      toAuthMockService(serviceId, rorInstance.mocksProvider.ldapServiceWith(serviceId))
    }
    val extAuthn = services.authenticationServices.map { serviceId =>
      toAuthMockService(serviceId, rorInstance.mocksProvider.externalAuthenticationServiceWith(serviceId))
    }
    val extAuthz = services.authorizationServices.map { serviceId =>
      toAuthMockService(serviceId, rorInstance.mocksProvider.externalAuthorizationServiceWith(serviceId))
    }
    AuthMockResponse.ProvideAuthMock.CurrentAuthMocks((ldaps ++ extAuthn ++ extAuthz).toList)
  }

  private def updateAuthMock(body: String)
                            (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val result = for {
      updateRequest <- decodeRequest(body)
      authServices <- readCurrentAuthServices()
      _ <- validateAuthMocks(updateRequest, authServices)
      result <- updateAuthMocks(updateRequest)
    } yield result

    result.value.map(_.merge)
  }

  private def decodeRequest(body: String): EitherT[Task, AuthMockResponse, UpdateMocksRequest] = {
    io.circe.parser.decode[UpdateMocksRequest](body)
      .leftMap(error => AuthMockResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage.show}]"))
      .leftWiden[AuthMockResponse]
      .toEitherT[Task]
  }

  private def readCurrentAuthServices()
                                     (implicit requestId: RequestId): EitherT[Task, AuthMockResponse, RorDependencies.Services] = {
    EitherT(withRorConfigAuthServices(
      action = identity,
      onNotSet = AuthMockResponse.UpdateAuthMock.NotConfigured.apply,
      onInvalidated = AuthMockResponse.UpdateAuthMock.Invalidated.apply
    ))
  }

  private def withRorConfigAuthServices[A, B](action: RorDependencies.Services => B,
                                              onNotSet: String => A,
                                              onInvalidated: String => A)
                                             (implicit requestId: RequestId): Task[Either[A, B]] = {
    rorInstance.currentTestSettings().map {
      case TestSettings.NotSet =>
        Left(onNotSet(testSettingsNotConfiguredMessage))
      case TestSettings.Present(_, dependencies, _, _) =>
        Right(action(dependencies.services))
      case _:TestSettings.Invalidated =>
        Left(onInvalidated(testSettingsInvalidatedMessage))
    }
  }

  private val testSettingsInvalidatedMessage = "ROR Test settings are invalidated. To use Auth Services Mock ROR has to have Test settings active."

  private val testSettingsNotConfiguredMessage = "ROR Test settings are not configured. To use Auth Services Mock ROR has to have Test settings active."

  private def validateAuthMocks(updateRequest: UpdateMocksRequest,
                                services: RorDependencies.Services): EitherT[Task, AuthMockResponse, Unit] = {
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
          s"ROR doesn't allow to configure unknown Auth Services. Only the ones used in ROR's Test settings can be configured. Unknown services: ${unknownServices.show}"
        )
      }
      .toEither
      .leftWiden[AuthMockResponse]
      .toEitherT[Task]
  }

  private def updateAuthMocks(updateRequest: UpdateMocksRequest)
                             (implicit requestId: RequestId): EitherT[Task, AuthMockResponse, AuthMockResponse] = EitherT {
    rorInstance
      .updateAuthMocks(toDomain(updateRequest.services))
      .map {
        case Right(()) =>
          Right(UpdateAuthMock.Success("Auth mock updated"))
        case Left(IndexSettingsUpdateError.TestSettingsNotSet) =>
          Left(AuthMockResponse.UpdateAuthMock.NotConfigured(testSettingsNotConfiguredMessage))
        case Left(IndexSettingsUpdateError.TestSettingsInvalidated) =>
          Left(AuthMockResponse.UpdateAuthMock.Invalidated(testSettingsInvalidatedMessage))
        case Left(IndexSettingsUpdateError.IndexSettingsSavingError(error)) =>
          Left(AuthMockResponse.UpdateAuthMock.Failed(s"Cannot save auth services mocks: ${error.show}"))
      }
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
  private[AuthMockApi] object AuthMockResponse {
    sealed trait ProvideAuthMock extends AuthMockResponse
    object ProvideAuthMock {
      final case class CurrentAuthMocks(services: List[AuthMockService]) extends ProvideAuthMock
      final case class NotConfigured(message: String) extends ProvideAuthMock
      final case class Invalidated(message: String) extends ProvideAuthMock
    }

    sealed trait UpdateAuthMock extends AuthMockResponse
    object UpdateAuthMock {
      final case class Success(message: String) extends UpdateAuthMock
      final case class NotConfigured(message: String) extends UpdateAuthMock
      final case class Invalidated(message: String) extends UpdateAuthMock
      final case class UnknownAuthServicesDetected(message: String) extends UpdateAuthMock
      final case class Failed(message: String) extends UpdateAuthMock
    }

    sealed trait Failure extends AuthMockResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  private[AuthMockApi] sealed trait AuthMockService
  private[AuthMockApi] object AuthMockService {
    final case class MockUser(name: NonEmptyString)
    final case class MockUserWithGroups(name: NonEmptyString, groups: List[MockGroup])
    final case class MockGroup(id: NonEmptyString, name: Option[NonEmptyString])

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

  implicit class AuthMockResponseOps(val authMockResponse: AuthMockResponse) extends AnyVal {

    type JSON = ujson.Value

    def statusCode: StatusCode = authMockResponse match {
      case _: AuthMockResponse.ProvideAuthMock => StatusCode.Ok
      case _: AuthMockResponse.UpdateAuthMock => StatusCode.Ok
      case _: AuthMockResponse.Failure.BadRequest => StatusCode.BadRequest
    }

    def body: JSON = {
      import Utils.codecs.authMockResponseEncoder
      ujson.read(authMockResponse.asJson.noSpaces)
    }
  }

  private object Utils {
    import AuthMockService.*

    final case class UpdateMocksRequest(services: List[AuthMockService])

    implicit class MockUserOps(val mock: MockUserWithGroups) extends AnyVal {
      def domainUserId: User.Id = User.Id(mock.name)

      def domainGroups: Set[Group] = mock.groups.map(toDomainGroup).toCovariantSet

      private def toDomainGroup(mockGroup: MockGroup) = {
        val id = GroupId(mockGroup.id)
        Group(id, mockGroup.name.map(GroupName.apply).getOrElse(GroupName.from(id)))
      }
    }

    def toAuthMockService(serviceId: LdapService#Id,
                          maybeMock: Option[LdapServiceMock]): AuthMockService = {
      val mockMode =
        maybeMock
          .map {
            _.users
              .map(user => MockUserWithGroups(user.id.value, user.groups.map(toMockGroup).toList))
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
              .map(user => MockUserWithGroups(user.id.value, user.groups.map(toMockGroup).toList))
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

    def toDomain(services: List[AuthMockService]): AuthServicesMocks = {
      services.foldLeft(AuthServicesMocks.empty) { (mocksProvider, service) =>
        service match {
          case LdapAuthorizationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case LdapAuthorizationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              ldapMocks = mocksProvider.ldapMocks +
                (LdapService.Name(name) -> LdapServiceMock(mock.users.map(toLdapMock).toCovariantSet))
            )
          case ExternalAuthenticationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case ExternalAuthenticationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              externalAuthenticationServiceMocks = mocksProvider.externalAuthenticationServiceMocks +
                (AuthenticationService.Name(name) -> ExternalAuthenticationServiceMock(users = mock.users.map(toAuthenticationMock).toCovariantSet))
            )
          case ExternalAuthorizationService(_, MockMode.NotConfigured) =>
            mocksProvider
          case ExternalAuthorizationService(name, MockMode.Enabled(mock)) =>
            mocksProvider.copy(
              externalAuthorizationServiceMocks = mocksProvider.externalAuthorizationServiceMocks +
                (AuthorizationService.Name(name) -> ExternalAuthorizationServiceMock(users = mock.users.map(toAuthorizationMock).toCovariantSet))
            )
        }
      }
    }

    private def toMockGroup(group: Group): MockGroup = MockGroup(id = group.id.value, name = Some(group.name.value))

    private def toLdapMock(user: MockUserWithGroups) = {
      MocksProvider.LdapServiceMock.LdapUserMock(id = user.domainUserId, groups = user.domainGroups)
    }

    private def toAuthorizationMock(user: MockUserWithGroups) = {
      MocksProvider.ExternalAuthorizationServiceMock.ExternalAuthorizationServiceUserMock(id = user.domainUserId, groups = user.domainGroups)
    }

    private def toAuthenticationMock(user: MockUser) = {
      MocksProvider.ExternalAuthenticationServiceMock.ExternalAuthenticationUserMock(id = User.Id(user.name))
    }

    object codecs {

      import AuthMockResponse.*

      implicit val nonEmptyStringCodec: Codec[NonEmptyString] = Codec.from(
        Decoder.decodeString.emap(NonEmptyString.from),
        Encoder.encodeString.contramap(_.value)
      )
      implicit val mockUserCodec: Codec[MockUser] = Codec.forProduct1("name")(MockUser.apply)(_.name)
      implicit val mockGroupCodec: Codec[MockGroup] =
        Codec.forProduct2("id", "name")(MockGroup.apply)(group => (group.id, group.name))
      implicit val mockServiceUserCodec: Codec[MockUserWithGroups] =
        Codec.forProduct2("name", "groups")(MockUserWithGroups.apply)(user => (user.name, user.groups))

      private def mockModeCodecFor[T: Encoder : Decoder]: Codec[MockMode[T]] = {
        val decoder: Decoder[MockMode[T]] = Decoder.instance { c =>
          c
            .as[String]
            .flatMap {
              case "NOT_CONFIGURED" => Right(MockMode.NotConfigured)
              case "" => Left(DecodingFailure(s"Mock type cannot be empty", ops = c.history))
              case other => Left(DecodingFailure(s"Unknown type of mock: ${other.show}", ops = c.history))
            }
            .orElse(Decoder[T].apply(c).map(MockMode.Enabled.apply))
        }
        val encoder: Encoder[MockMode[T]] = Encoder.encodeJson.contramap {
          case MockMode.NotConfigured => "NOT_CONFIGURED".asJson
          case MockMode.Enabled(configuredMock) => Encoder[T].apply(configuredMock)
        }
        Codec.from(decoder, encoder)
      }

      implicit val ldapAuthorizationServiceCodec: Codec[LdapAuthorizationService] = {
        implicit val mockCodec: Codec[LdapAuthorizationService.Mock] =
          Codec.forProduct1("users")(LdapAuthorizationService.Mock.apply)(_.users)
        implicit val mockModeCodec: Codec[MockMode[LdapAuthorizationService.Mock]] =
          mockModeCodecFor[LdapAuthorizationService.Mock]

        Codec.forProduct2("name", "mock")(
          LdapAuthorizationService.apply
        )(mock => (mock.name, mock.mock))
      }

      implicit val externalAuthenticationServiceCodec: Codec[ExternalAuthenticationService] = {
        implicit val mockCodec: Codec[ExternalAuthenticationService.Mock] =
          Codec.forProduct1("users")(ExternalAuthenticationService.Mock.apply)(_.users)
        implicit val mockModeCodec: Codec[MockMode[ExternalAuthenticationService.Mock]] =
          mockModeCodecFor[ExternalAuthenticationService.Mock]

        Codec.forProduct2("name", "mock")(
          ExternalAuthenticationService.apply
        )(mock => (mock.name, mock.mock))

      }

      implicit val externalAuthorizationServiceCodec: Codec[ExternalAuthorizationService] = {
        implicit val mockCodec: Codec[ExternalAuthorizationService.Mock] =
          Codec.forProduct1("users")(ExternalAuthorizationService.Mock.apply)(_.users)
        implicit val mockModeCodec: Codec[MockMode[ExternalAuthorizationService.Mock]] =
          mockModeCodecFor[ExternalAuthorizationService.Mock]

        Codec.forProduct2("name", "mock")(
          ExternalAuthorizationService.apply
        )(mock => (mock.name, mock.mock))
      }

      implicit val authMockServiceCodec: Codec[AuthMockService] = {
        val decoder: Decoder[AuthMockService] = Decoder.instance { c =>
          for {
            serviceType <- c.downField("type").as[String]
            service <- serviceType match {
              case "LDAP" => Decoder[LdapAuthorizationService].apply(c)
              case "EXT_AUTHN" => Decoder[ExternalAuthenticationService].apply(c)
              case "EXT_AUTHZ" => Decoder[ExternalAuthorizationService].apply(c)
              case other => Left(DecodingFailure(s"Unknown auth mock service type: ${other.show}", Nil))
            }
          } yield service
        }
        val encoder: Encoder[AuthMockService] = Encoder.instance {
          case service: LdapAuthorizationService =>
            Json.obj("type" -> Json.fromString("LDAP"))
              .deepMerge(Encoder[LdapAuthorizationService].apply(service))
          case service: ExternalAuthenticationService =>
            Json.obj("type" -> Json.fromString("EXT_AUTHN"))
              .deepMerge(Encoder[ExternalAuthenticationService].apply(service))
          case service: ExternalAuthorizationService =>
            Json.obj("type" -> Json.fromString("EXT_AUTHZ"))
              .deepMerge(Encoder[ExternalAuthorizationService].apply(service))
        }
        Codec.from(decoder, encoder)
      }

      implicit val authMockResponseEncoder: Encoder[AuthMockResponse] = {
        implicit val provideAuthMockResponseEncoder: Encoder[ProvideAuthMock] = {
          val currentAuthMocksResponseEncoder: Encoder[AuthMockResponse.ProvideAuthMock.CurrentAuthMocks] =
            Encoder.forProduct2("status", "services")(response =>
              ("TEST_SETTINGS_PRESENT", response.services)
            )
          Encoder.instance {
            case response: ProvideAuthMock.CurrentAuthMocks =>
              currentAuthMocksResponseEncoder.apply(response)
            case ProvideAuthMock.NotConfigured(message) =>
              Map("status" -> "TEST_SETTINGS_NOT_CONFIGURED", "message" -> message).asJson
            case ProvideAuthMock.Invalidated(message) =>
              Map("status" -> "TEST_SETTINGS_INVALIDATED", "message" -> message).asJson
          }
        }

        implicit val updateAuthMockResponseEncoder: Encoder[UpdateAuthMock] = {
          def toJson(status: String, message: String): Json =
            Map("status" -> status, "message" -> message).asJson

          Encoder.instance {
            case UpdateAuthMock.Success(message) =>
              toJson("OK", message)
            case UpdateAuthMock.NotConfigured(message) =>
              toJson("TEST_SETTINGS_NOT_CONFIGURED", message)
            case UpdateAuthMock.Invalidated(message) =>
              toJson("TEST_SETTINGS_INVALIDATED", message)
            case UpdateAuthMock.UnknownAuthServicesDetected(message) =>
              toJson("UNKNOWN_AUTH_SERVICES_DETECTED", message)
            case UpdateAuthMock.Failed(message) =>
              toJson("FAILED", message)
          }
        }

        implicit val failureEncoder: Encoder[Failure] = Encoder.instance {
          case Failure.BadRequest(message) => Map("status" -> "FAILED", "message" -> message).asJson
        }

        Encoder.instance {
          case response: ProvideAuthMock => Encoder[ProvideAuthMock].apply(response)
          case response: UpdateAuthMock => Encoder[UpdateAuthMock].apply(response)
          case response: Failure => Encoder[Failure].apply(response)
        }
      }

      implicit val updateRequestDecoder: Decoder[UpdateMocksRequest] = Decoder.forProduct1("services")(UpdateMocksRequest.apply)
    }

  }
}