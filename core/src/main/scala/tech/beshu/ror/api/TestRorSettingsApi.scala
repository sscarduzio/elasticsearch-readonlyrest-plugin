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
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning
import tech.beshu.ror.accesscontrol.domain.{LoggedUser, RequestId}
import tech.beshu.ror.api.TestRorSettingsApi.TestSettingsRequest.Type
import tech.beshu.ror.api.TestRorSettingsApi.TestSettingsResponse.*
import tech.beshu.ror.api.TestRorSettingsApi.{TestSettingsRequest, TestSettingsResponse}
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexSettingsInvalidationError, RawSettingsReloadError, TestSettings}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.{RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.CirceOps.toCirceErrorOps
import tech.beshu.ror.utils.DurationOps.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.util.Try

class TestRorSettingsApi(rorInstance: RorInstance,
                         settingsYamlParser: RawRorSettingsYamlParser) {

  import tech.beshu.ror.api.TestRorSettingsApi.Utils.*
  import tech.beshu.ror.api.TestRorSettingsApi.Utils.decoders.*

  def call(request: RorApiRequest[TestSettingsRequest])
          (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    val testSettingsResponse = request.request.aType match {
      case Type.ProvideTestSettings => loadCurrentTestSettings()
      case Type.UpdateTestSettings => updateTestSettings(request.request.body)
      case Type.InvalidateTestSettings => invalidateTestSettings()
      case Type.ProvideLocalUsers => provideLocalUsers(request.loggedUser)
    }
    testSettingsResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateTestSettings(body: String)
                                (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    val result = for {
      updateRequest <- EitherT.fromEither[Task](decodeUpdateSettingsRequest(body))
      rorSettings <- rorTestSettingsFrom(updateRequest.settingsString)
      response <- forceReloadTestSettings(rorSettings, updateRequest.ttl)
    } yield response

    result.value.map(_.merge)
  }

  private def decodeUpdateSettingsRequest(payload: String): Either[Failure, UpdateTestSettingsRequest] = {
    io.circe.parser.decode[UpdateTestSettingsRequest](payload)
      .left.map(error => TestSettingsResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage}]"))
  }

  private def rorTestSettingsFrom(settingsString: String): EitherT[Task, TestSettingsResponse, RawRorSettings] = EitherT {
    settingsYamlParser
      .fromString(settingsString)
      .map(_.left.map(error => TestSettingsResponse.UpdateTestSettings.FailedResponse(error.show)))
  }

  private def invalidateTestSettings()
                                    (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .invalidateTestSettingsEngine()
      .map {
        case Right(()) =>
          TestSettingsResponse.InvalidateTestSettings.SuccessResponse("ROR Test settings are invalidated")
        case Left(IndexSettingsInvalidationError.IndexSettingsSavingError(error)) =>
          TestSettingsResponse.InvalidateTestSettings.FailedResponse(s"Cannot invalidate test settings: ${error.show}")
      }
  }

  private def loadCurrentTestSettings()
                                     (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .currentTestSettings()
      .map {
        case TestSettings.NotSet =>
          TestSettingsResponse.ProvideTestSettings.TestSettingsNotConfigured("ROR Test settings are not configured")
        case TestSettings.Present(rawSettings, dependencies, configuredTtl, validTo) =>
          TestSettingsResponse.ProvideTestSettings.CurrentTestSettings(
            ttl = apiFormat(configuredTtl),
            validTo = validTo,
            settings = rawSettings,
            warnings = dependencies.impersonationWarningsReader.read().map(toWarningDto)
          )
        case TestSettings.Invalidated(recentConfig, ttl) =>
          TestSettingsResponse.ProvideTestSettings.TestSettingsInvalidated("ROR Test settings are invalidated", recentConfig, apiFormat(ttl))
      }
  }

  private def provideLocalUsers(loggedUser: Option[LoggedUser])
                               (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .currentTestSettings()
      .map {
        case TestSettings.NotSet =>
          TestSettingsResponse.ProvideLocalUsers.TestSettingsNotConfigured("ROR Test settings are not configured")
        case TestSettings.Present(_, dependencies, _, _) =>
          val filteredLocalUsers = dependencies.localUsers.users -- loggedUser.map(_.id)
          TestSettingsResponse.ProvideLocalUsers.SuccessResponse(
            users = filteredLocalUsers.map(_.value.value).toList,
            unknownUsers = dependencies.localUsers.unknownUsers
          )
        case _: TestSettings.Invalidated =>
          TestSettingsResponse.ProvideLocalUsers.TestSettingsInvalidated("ROR Test settings are invalidated")
      }
  }

  private def forceReloadTestSettings(settings: RawRorSettings,
                                      ttl: PositiveFiniteDuration)
                                     (implicit requestId: RequestId): EitherT[Task, TestSettingsResponse, TestSettingsResponse] = {
    EitherT(
      rorInstance
        .forceReloadTestSettingsEngine(settings, ttl)
        .map {
          _
            .map { newTestSettings =>
              TestSettingsResponse.UpdateTestSettings.SuccessResponse(
                message = "updated settings",
                validTo = newTestSettings.validTo,
                warnings = newTestSettings.dependencies.impersonationWarningsReader.read().map(toWarningDto)
              )
            }
            .leftMap {
              case IndexSettingsSavingError(error) =>
                TestSettingsResponse.UpdateTestSettings.FailedResponse(s"Cannot reload new settings: ${error.show}")
              case ReloadError(RawSettingsReloadError.SettingsUpToDate(_)) =>
                TestSettingsResponse.UpdateTestSettings.FailedResponse(s"Current settings are already loaded")
              case ReloadError(RawSettingsReloadError.RorInstanceStopped) =>
                TestSettingsResponse.UpdateTestSettings.FailedResponse(s"ROR instance is being stopped")
              case ReloadError(RawSettingsReloadError.ReloadingFailed(failure)) =>
                TestSettingsResponse.UpdateTestSettings.FailedResponse(s"Cannot reload new settings: ${failure.message}")
            }
        }
    )
  }

  private def toWarningDto(warning: ImpersonationWarning): TestSettingsResponse.Warning = {
    TestSettingsResponse.Warning(
      blockName = warning.block.value,
      ruleName = warning.ruleName.value,
      message = warning.message.value,
      hint = warning.hint
    )
  }
}

object TestRorSettingsApi {

  final case class TestSettingsRequest(aType: TestSettingsRequest.Type,
                                       body: String)

  object TestSettingsRequest {
    sealed trait Type
    object Type {
      case object ProvideTestSettings extends Type
      case object UpdateTestSettings extends Type
      case object InvalidateTestSettings extends Type
      case object ProvideLocalUsers extends Type
    }
  }

  sealed trait TestSettingsResponse
  object TestSettingsResponse {

    final case class Warning(blockName: String,
                             ruleName: String,
                             message: String,
                             hint: String)

    sealed trait ProvideTestSettings extends TestSettingsResponse
    object ProvideTestSettings {
      final case class CurrentTestSettings(ttl: FiniteDuration,
                                           validTo: Instant,
                                           settings: RawRorSettings,
                                           warnings: List[Warning]) extends ProvideTestSettings

      final case class TestSettingsNotConfigured(message: String) extends ProvideTestSettings
      final case class TestSettingsInvalidated(message: String,
                                               settings: RawRorSettings,
                                               ttl: FiniteDuration) extends ProvideTestSettings
    }

    sealed trait UpdateTestSettings extends TestSettingsResponse
    object UpdateTestSettings {
      final case class SuccessResponse(message: String, validTo: Instant, warnings: List[Warning]) extends UpdateTestSettings
      final case class FailedResponse(message: String) extends UpdateTestSettings
    }

    sealed trait InvalidateTestSettings extends TestSettingsResponse
    object InvalidateTestSettings {
      final case class SuccessResponse(message: String) extends InvalidateTestSettings
      final case class FailedResponse(message: String) extends InvalidateTestSettings
    }

    sealed trait ProvideLocalUsers extends TestSettingsResponse
    object ProvideLocalUsers {
      final case class SuccessResponse(users: List[String], unknownUsers: Boolean) extends ProvideLocalUsers
      final case class TestSettingsNotConfigured(message: String) extends ProvideLocalUsers
      final case class TestSettingsInvalidated(message: String) extends ProvideLocalUsers
    }

    sealed trait Failure extends TestSettingsResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  implicit class StatusFromTestSettingsResponse(val response: TestSettingsResponse) extends AnyVal {
    def status: String = response match {
      case _: ProvideTestSettings.CurrentTestSettings => "TEST_SETTINGS_PRESENT"
      case _: ProvideTestSettings.TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: ProvideTestSettings.TestSettingsInvalidated => "TEST_SETTINGS_INVALIDATED"
      case _: UpdateTestSettings.SuccessResponse => "OK"
      case _: UpdateTestSettings.FailedResponse => "FAILED"
      case _: InvalidateTestSettings.SuccessResponse => "OK"
      case _: InvalidateTestSettings.FailedResponse => "FAILED"
      case _: ProvideLocalUsers.SuccessResponse => "OK"
      case _: ProvideLocalUsers.TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: ProvideLocalUsers.TestSettingsInvalidated => "TEST_SETTINGS_INVALIDATED"
      case _: Failure.BadRequest => "FAILED"
    }
  }

  private object Utils {
    final case class UpdateTestSettingsRequest(settingsString: String,
                                               ttl: PositiveFiniteDuration)

    private def parseDuration(value: String): Either[String, PositiveFiniteDuration] = {
      Try(Duration(value))
        .toEither
        .leftMap(_ => s"Cannot parse '${value.show}' as duration.")
        .flatMap(_.toRefinedPositive)
    }

    object decoders {
      implicit val durationDecoder: Decoder[PositiveFiniteDuration] = Decoder.decodeString.emap(parseDuration)

      implicit val updateTestSettingsRequestDecoder: Decoder[UpdateTestSettingsRequest] =
        Decoder.forProduct2("settings", "ttl")(UpdateTestSettingsRequest.apply)
    }

    def apiFormat(duration: PositiveFiniteDuration): FiniteDuration = {
      duration.value.toCoarsest
    }
  }
}
