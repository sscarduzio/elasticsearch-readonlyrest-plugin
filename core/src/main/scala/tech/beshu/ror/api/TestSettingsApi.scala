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
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.api.TestSettingsApi.TestSettingsRequest.Type
import tech.beshu.ror.api.TestSettingsApi.TestSettingsResponse.UpdateTestSettings
import tech.beshu.ror.api.TestSettingsApi.{TestSettingsRequest, TestSettingsResponse}
import tech.beshu.ror.boot.RorInstance.{RawConfigReloadError, TestSettings}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.RawRorConfig

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Try

class TestSettingsApi(rorInstance: RorInstance)
                     (implicit clock: Clock) {

  def call(request: TestSettingsRequest)
          (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    val testSettingsResponse = request.aType match {
      case Type.ProvideTestSettings => loadCurrentTestSettings()
      case Type.UpdateTestSettings => updateTestSettings(request.body)
      case Type.InvalidateTestSettings => invalidateTestSettings()
      case Type.ProvideLocalUsers => provideLocalUsers()
    }
    testSettingsResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateTestSettings(body: String)
                                (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    val result = for {
      config <- rorConfigAndTtlFrom(body)
      _ <- forceReloadTestSettings(config._1, config._2)
    } yield ()
    result.value.map {
      case Right(_) => TestSettingsResponse.UpdateTestSettings.SuccessResponse("updated settings")
      case Left(failure) => failure
    }
  }

  private def invalidateTestSettings()
                                    (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .invalidateImpersonationEngine()
      .map { _ =>
        TestSettingsResponse.InvalidateTestSettings.SuccessResponse("ROR Test settings are invalidated")
      }
  }

  private def rorConfigAndTtlFrom(payload: String): EitherT[Task, UpdateTestSettings.FailedResponse, (RawRorConfig, FiniteDuration)] = {
    for {
      json <- EitherT.fromEither[Task](io.circe.parser.parse(payload).left.map(_ => TestSettingsResponse.UpdateTestSettings.FailedResponse("JSON body malformed")))
      ttl <- EitherT.fromEither[Task](ttlValue(json))
      rorConfig <- settingsValue(json)
    } yield (rorConfig, ttl)
  }

  private def settingsValue(json: io.circe.Json) = {
    def liftFailure(failureMessage: String) = EitherT.leftT[Task, RawRorConfig](TestSettingsResponse.UpdateTestSettings.FailedResponse(failureMessage))

    json \\ "settings" match {
      case Nil =>
        liftFailure("Malformed settings payload - no settings key")
      case configJsonValue :: Nil =>
        configJsonValue.asString match {
          case Some(configString) =>
            EitherT(RawRorConfig.fromString(configString).map(_.left.map(error => TestSettingsResponse.UpdateTestSettings.FailedResponse(error.show))))
          case None =>
            liftFailure("Malformed settings payload - settings key value is not string")
        }
      case _ =>
        liftFailure("Malformed settings payload - only one settings value allowed")
    }
  }

  private def ttlValue(json: io.circe.Json) = {
    def liftFailure(failureMessage: String) = Left(TestSettingsResponse.UpdateTestSettings.FailedResponse(failureMessage))

    json \\ "ttl" match {
      case Nil =>
        liftFailure("Malformed ttl payload - no ttl key")
      case ttlJsonValue :: Nil =>
        ttlJsonValue.asString match {
          case Some(ttlString) =>
            parseDuration(ttlString).left.map(error => TestSettingsResponse.UpdateTestSettings.FailedResponse(error))
          case None =>
            liftFailure("Malformed ttl payload - ttl key value is not string")
        }
      case _ =>
        liftFailure("Malformed ttl payload - only one ttl value allowed")
    }
  }

  private def parseDuration(value: String): Either[String, FiniteDuration] = {
    Try(Duration(value)).toOption match {
      case Some(v: FiniteDuration) if v.toMillis > 0 => Right(v)
      case Some(_) | None =>
        Left(s"Cannot parse '$value' as duration.")
    }
  }

  private def loadCurrentTestSettings()
                                     (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .currentTestSettings()
      .map {
        case TestSettings.NotConfigured =>
          TestSettingsResponse.ProvideTestSettings.TestSettingsNotConfigured("ROR Test settings are not configured")
        case TestSettings.Present(config, _, validTo) =>
          val ttl = getTtl(validTo)
          TestSettingsResponse.ProvideTestSettings.CurrentTestSettings(
            ttl = ttl,
            validTo = validTo,
            settings = config,
            warnings = List.empty
          )
        case TestSettings.Invalidated(recent) =>
          TestSettingsResponse.ProvideTestSettings.TestSettingsInvalidated("ROR Test settings are invalidated", recent)
      }
  }

  private def provideLocalUsers()
                               (implicit requestId: RequestId): Task[TestSettingsResponse] = {
    rorInstance
      .currentTestSettings()
      .map {
        case TestSettings.NotConfigured =>
          TestSettingsResponse.ProvideLocalUsers.TestSettingsNotConfigured("ROR Test settings are not configured")
        case _: TestSettings.Present =>
          TestSettingsResponse.ProvideLocalUsers.SuccessResponse(users = List.empty, unknownUsers = false)
        case _: TestSettings.Invalidated =>
          TestSettingsResponse.ProvideLocalUsers.TestSettingsNotConfigured("ROR Test settings are not configured")
      }
  }

  private def forceReloadTestSettings(config: RawRorConfig,
                                      ttl: FiniteDuration)
                                     (implicit requestId: RequestId) = {
    EitherT(
      rorInstance
        .forceReloadImpersonatorsEngine(config, ttl)
        .map {
          _.leftMap {
            case RawConfigReloadError.ReloadingFailed(failure) =>
              TestSettingsResponse.UpdateTestSettings.FailedResponse(s"Cannot reload new settings: ${failure.message}")
            case RawConfigReloadError.ConfigUpToDate(_) =>
              TestSettingsResponse.UpdateTestSettings.FailedResponse(s"Current settings are already loaded")
            case RawConfigReloadError.RorInstanceStopped =>
              TestSettingsResponse.UpdateTestSettings.FailedResponse(s"ROR instance is being stopped")
          }
        }
    )
  }

  private def getTtl(validTo: Instant): FiniteDuration = {
    val ttlMillis = Math.max(0, validTo.toEpochMilli - clock.instant().toEpochMilli)
    val ttlSeconds =  FiniteDuration(ttlMillis, TimeUnit.MILLISECONDS).toSeconds
    FiniteDuration(ttlSeconds, TimeUnit.SECONDS)
  }
}

object TestSettingsApi {

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

  sealed trait TestSettingsResponse {
    def status: String
  }

  object TestSettingsResponse {

    object ProvideTestSettings {
      final case class Warning(blockName: String,
                               ruleName: String,
                               message: String,
                               hint: String)

      final case class CurrentTestSettings(ttl: FiniteDuration,
                                           validTo: Instant,
                                           settings: RawRorConfig,
                                           warnings: List[Warning]) extends TestSettingsResponse {
        override val status: String = "TEST_SETTINGS_PRESENT"
      }

      final case class TestSettingsNotConfigured(message: String) extends TestSettingsResponse {
        override val status: String = "TEST_SETTINGS_NOT_CONFIGURED"
      }

      final case class TestSettingsInvalidated(message: String, settings: RawRorConfig) extends TestSettingsResponse {
        override val status: String = "TEST_SETTINGS_INVALIDATED"
      }
    }

    object UpdateTestSettings {
      final case class SuccessResponse(message: String) extends TestSettingsResponse {
        override val status: String = "OK"
      }

      final case class FailedResponse(message: String) extends TestSettingsResponse {
        override val status: String = "FAILED"
      }
    }

    object InvalidateTestSettings {
      final case class SuccessResponse(message: String) extends TestSettingsResponse {
        override val status: String = "OK"
      }
    }

    object ProvideLocalUsers {
      final case class SuccessResponse(users: List[String], unknownUsers: Boolean) extends TestSettingsResponse {
        override val status: String = "OK"
      }

      final case class TestSettingsNotConfigured(message: String) extends TestSettingsResponse {
        override val status: String = "TEST_SETTINGS_NOT_CONFIGURED"
      }
    }

   final case class Failure(message: String) extends TestSettingsResponse {
     override val status: String = "FAILED"
   }

    def notAvailable: TestSettingsResponse = Failure("Service not available")
    def internalError: TestSettingsResponse = Failure("Internal error")
  }
}
