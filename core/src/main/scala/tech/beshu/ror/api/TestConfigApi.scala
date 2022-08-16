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

import java.time.{Clock, Instant}

import cats.data.EitherT
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.api.TestConfigApi.TestConfigRequest.Type
import tech.beshu.ror.api.TestConfigApi.TestConfigResponse._
import tech.beshu.ror.api.TestConfigApi.{TestConfigRequest, TestConfigResponse}
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexConfigInvalidationError, RawConfigReloadError, TestConfig}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.utils.CirceOps.toCirceErrorOps

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class TestConfigApi(rorInstance: RorInstance)
                   (implicit clock: Clock) {

  import tech.beshu.ror.api.TestConfigApi.Utils._
  import tech.beshu.ror.api.TestConfigApi.Utils.decoders._

  def call(request: TestConfigRequest)
          (implicit requestId: RequestId): Task[TestConfigResponse] = {
    val testConfigResponse = request.aType match {
      case Type.ProvideTestConfig => loadCurrentTestConfig()
      case Type.UpdateTestConfig => updateTestConfig(request.body)
      case Type.InvalidateTestConfig => invalidateTestConfig()
      case Type.ProvideLocalUsers => provideLocalUsers()
    }
    testConfigResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateTestConfig(body: String)
                              (implicit requestId: RequestId): Task[TestConfigResponse] = {
    val result = for {
      updateRequest <- EitherT.fromEither[Task](decodeUpdateConfigRequest(body))
      rorConfig <- rorTestConfig(updateRequest.configString)
      _ <- forceReloadTestConfig(rorConfig, updateRequest.ttl)
    } yield TestConfigResponse.UpdateTestConfig.SuccessResponse("updated settings")

    result.value.map(_.merge)
  }

  private def decodeUpdateConfigRequest(payload: String): Either[Failure, UpdateTestConfigRequest] = {
    io.circe.parser.decode[UpdateTestConfigRequest](payload)
      .left.map(error => TestConfigResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage}]"))
  }

  private def rorTestConfig(configString: String): EitherT[Task, TestConfigResponse, RawRorConfig] = EitherT {
    RawRorConfig.fromString(configString).map(_.left.map(error => TestConfigResponse.UpdateTestConfig.FailedResponse(error.show)))
  }

  private def invalidateTestConfig()
                                  (implicit requestId: RequestId): Task[TestConfigResponse] = {
    rorInstance
      .invalidateTestConfigEngine()
      .map {
        case Right(()) =>
          TestConfigResponse.InvalidateTestConfig.SuccessResponse("ROR Test settings are invalidated")
        case Left(IndexConfigInvalidationError.IndexConfigSavingError(error)) =>
          TestConfigResponse.InvalidateTestConfig.FailedResponse(s"Cannot invalidate test settings: ${error.show}")
      }
  }

  private def loadCurrentTestConfig()
                                   (implicit requestId: RequestId): Task[TestConfigResponse] = {
    rorInstance
      .currentTestConfig()
      .map {
        case TestConfig.NotSet =>
          TestConfigResponse.ProvideTestConfig.TestSettingsNotConfigured("ROR Test settings are not configured")
        case TestConfig.Present(config, rawConfig, configuredTtl, validTo) =>
          TestConfigResponse.ProvideTestConfig.CurrentTestSettings(
            ttl = apiFormat(configuredTtl),
            validTo = validTo,
            settings = rawConfig,
            warnings = config.impersonationWarningsReader.read().map { warning =>
              TestConfigResponse.ProvideTestConfig.Warning(
                blockName = warning.block.value,
                ruleName = warning.ruleName.value,
                message = warning.message.value,
                hint = warning.hint
              )
            }
          )
        case TestConfig.Invalidated(recentConfig, ttl) =>
          TestConfigResponse.ProvideTestConfig.TestSettingsInvalidated("ROR Test settings are invalidated", recentConfig, apiFormat(ttl))
      }
  }

  private def provideLocalUsers()
                               (implicit requestId: RequestId): Task[TestConfigResponse] = {
    rorInstance
      .currentTestConfig()
      .map {
        case TestConfig.NotSet =>
          TestConfigResponse.ProvideLocalUsers.TestSettingsNotConfigured("ROR Test settings are not configured")
        case TestConfig.Present(config, _, _, _) =>
          TestConfigResponse.ProvideLocalUsers.SuccessResponse(
            users = config.localUsers.users.map(_.value.value).toList,
            unknownUsers = config.localUsers.unknownUsers
          )
        case _:TestConfig.Invalidated =>
          TestConfigResponse.ProvideLocalUsers.TestSettingsInvalidated("ROR Test settings are invalidated")
      }
  }

  private def forceReloadTestConfig(config: RawRorConfig,
                                    ttl: FiniteDuration Refined Positive)
                                   (implicit requestId: RequestId): EitherT[Task, TestConfigResponse, Unit] = {
    EitherT(
      rorInstance
        .forceReloadTestConfigEngine(config, ttl)
        .map {
          _.leftMap {
            case IndexConfigSavingError(error) =>
              TestConfigResponse.UpdateTestConfig.FailedResponse(s"Cannot reload new settings: ${error.show}")
            case ReloadError(RawConfigReloadError.ConfigUpToDate(_)) =>
              TestConfigResponse.UpdateTestConfig.FailedResponse(s"Current settings are already loaded")
            case ReloadError(RawConfigReloadError.RorInstanceStopped) =>
              TestConfigResponse.UpdateTestConfig.FailedResponse(s"ROR instance is being stopped")
            case ReloadError(RawConfigReloadError.ReloadingFailed(failure)) =>
              TestConfigResponse.UpdateTestConfig.FailedResponse(s"Cannot reload new settings: ${failure.message}")
          }
        }
    )
  }
}

object TestConfigApi {

  final case class TestConfigRequest(aType: TestConfigRequest.Type,
                                     body: String)

  object TestConfigRequest {
    sealed trait Type
    object Type {
      case object ProvideTestConfig extends Type
      case object UpdateTestConfig extends Type
      case object InvalidateTestConfig extends Type
      case object ProvideLocalUsers extends Type
    }
  }

  sealed trait TestConfigResponse
  object TestConfigResponse {

    sealed trait ProvideTestConfig extends TestConfigResponse
    object ProvideTestConfig {
      final case class Warning(blockName: String,
                               ruleName: String,
                               message: String,
                               hint: String)

      final case class CurrentTestSettings(ttl: FiniteDuration,
                                           validTo: Instant,
                                           settings: RawRorConfig,
                                           warnings: List[Warning]) extends ProvideTestConfig

      final case class TestSettingsNotConfigured(message: String) extends ProvideTestConfig
      final case class TestSettingsInvalidated(message: String,
                                               settings: RawRorConfig,
                                               ttl: FiniteDuration) extends ProvideTestConfig
    }

    sealed trait UpdateTestConfig extends TestConfigResponse
    object UpdateTestConfig {
      final case class SuccessResponse(message: String) extends UpdateTestConfig
      final case class FailedResponse(message: String) extends UpdateTestConfig
    }

    sealed trait InvalidateTestConfig extends TestConfigResponse
    object InvalidateTestConfig {
      final case class SuccessResponse(message: String) extends InvalidateTestConfig
      final case class FailedResponse(message: String) extends InvalidateTestConfig
    }

    sealed trait ProvideLocalUsers extends TestConfigResponse
    object ProvideLocalUsers {
      final case class SuccessResponse(users: List[String], unknownUsers: Boolean) extends ProvideLocalUsers
      final case class TestSettingsNotConfigured(message: String) extends ProvideLocalUsers
      final case class TestSettingsInvalidated(message: String) extends ProvideLocalUsers
    }

    sealed trait Failure extends TestConfigResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  implicit class StatusFromTestConfigResponse(val response: TestConfigResponse) extends AnyVal {
    def status: String = response match {
      case _: ProvideTestConfig.CurrentTestSettings => "TEST_SETTINGS_PRESENT"
      case _: ProvideTestConfig.TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: ProvideTestConfig.TestSettingsInvalidated => "TEST_SETTINGS_INVALIDATED"
      case _: UpdateTestConfig.SuccessResponse => "OK"
      case _: UpdateTestConfig.FailedResponse => "FAILED"
      case _: InvalidateTestConfig.SuccessResponse => "OK"
      case _: InvalidateTestConfig.FailedResponse => "FAILED"
      case _: ProvideLocalUsers.SuccessResponse => "OK"
      case _: ProvideLocalUsers.TestSettingsNotConfigured => "TEST_SETTINGS_NOT_CONFIGURED"
      case _: ProvideLocalUsers.TestSettingsInvalidated => "TEST_SETTINGS_INVALIDATED"
      case _: Failure.BadRequest => "FAILED"
    }
  }

  private object Utils {
    final case class UpdateTestConfigRequest(configString: String,
                                             ttl: FiniteDuration Refined Positive)

    private def parseDuration(value: String): Either[String, FiniteDuration Refined Positive] = {
      import tech.beshu.ror.accesscontrol.refined.finiteDurationValidate

      Try(Duration(value)).toOption match {
        case Some(v: FiniteDuration) if v.toMillis > 0 =>
          refineV[Positive](v)
        case Some(_) | None =>
          Left(s"Cannot parse '$value' as duration.")
      }
    }

    object decoders {
      implicit val durationDecoder: Decoder[FiniteDuration Refined Positive] = Decoder.decodeString.emap(parseDuration)

      implicit val updateTestConfigRequestDecoder: Decoder[UpdateTestConfigRequest] =
        Decoder.forProduct2("settings", "ttl")(UpdateTestConfigRequest.apply)
    }

    def apiFormat(duration: FiniteDuration Refined Positive): FiniteDuration = {
      duration.value.toCoarsest
    }
  }
}
