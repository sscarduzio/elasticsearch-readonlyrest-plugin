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
package tech.beshu.ror.adminapi

import cats.data.{EitherT, NonEmptyList}
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.adminapi.AdminRestApi.AdminRequest.Type
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexConfigReloadError, RawConfigReloadError}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.SpecializedError
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.configuration.{IndexConfigManager, RawRorConfig}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

class AdminRestApi(rorInstance: RorInstance,
                   indexConfigManager: IndexConfigManager,
                   fileConfigLoader: FileConfigLoader,
                   rorConfigurationIndex: RorConfigurationIndex)
  extends Logging {

  import AdminRestApi._

  def call(request: AdminRequest): Task[AdminResponse] = {
    val apiCallResult = request.aType match {
      case Type.ForceReload => forceReloadRor()
      case Type.ProvideIndexConfig => provideRorIndexConfig()
      case Type.ProvideFileConfig => provideRorFileConfig()
      case Type.UpdateIndexConfig => updateIndexConfiguration(request.body)
      case Type.UpdateTestConfig => updateTestConfiguration(request.body, testConfigTtlFrom(request.headers))
      case Type.InvalidateTestConfig => invalidateTestConfiguration()
      case Type.CurrentUserMetadata => Task.now(Success("ok"))
    }
    apiCallResult
      .map(AdminResponse(_))
      .executeOn(RorSchedulers.adminRestApiScheduler)
  }

  private def forceReloadRor(): Task[ApiCallResult] = {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(_) => Success("ReadonlyREST settings were reloaded with success!")
        case Left(IndexConfigReloadError.LoadingConfigError(error)) => Failure(error.show)
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate)) => Failure("Current settings are already loaded")
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)) => Failure("ROR is stopped")
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(failure))) => Failure(s"Cannot reload new settings: ${failure.message}")
      }
  }

  private def updateIndexConfiguration(body: String): Task[ApiCallResult] = {
    val result = for {
      config <- rorConfigFrom(body)
      _ <- forceReloadAndSaveNewConfig(config)
    } yield ()
    result.value.map {
      case Right(_) => Success("updated settings")
      case Left(failure) => failure
    }
  }

  private def updateTestConfiguration(body: String,
                                      ttl: Option[FiniteDuration]): Task[ApiCallResult] = {
    val result = for {
      config <- rorConfigFrom(body)
      _ <- forceReloadTestConfig(config, ttl)
    } yield ()
    result.value.map {
      case Right(_) => Success("updated settings")
      case Left(failure) => failure
    }
  }

  private def invalidateTestConfiguration(): Task[ApiCallResult] = {
    rorInstance
      .invalidateImpersonationEngine()
      .map { _ =>
        Success("test settings invalidated")
      }
  }

  private def provideRorFileConfig(): Task[ApiCallResult] = {
    fileConfigLoader
      .load()
      .map {
        case Right(config) => Success(config.raw)
        case Left(error) => Failure(error.show)
      }
  }

  private def provideRorIndexConfig(): Task[ApiCallResult] = {
    indexConfigManager
      .load(rorConfigurationIndex)
      .map {
        case Right(config) =>
          Success(config.raw)
        case Left(SpecializedError(error: IndexConfigNotExist.type)) =>
          implicit val show = IndexConfigError.show.contramap(identity[IndexConfigNotExist.type])
          ConfigNotFound(error.show)
        case Left(error) =>
          Failure(error.show)
      }
  }

  private def rorConfigFrom(payload: String) = {
    for {
      json <- EitherT.fromEither[Task](io.circe.parser.parse(payload).left.map(_ => Failure("JSON body malformed")))
      rorConfig <- settingsValue(json)
    } yield rorConfig
  }

  private def settingsValue(json: io.circe.Json) = {
    def liftFailure(failureMessage: String) = EitherT.leftT[Task, RawRorConfig](Failure(failureMessage))

    json \\ "settings" match {
      case Nil =>
        liftFailure("Malformed settings payload - no settings key")
      case configJsonValue :: Nil =>
        configJsonValue.asString match {
          case Some(configString) =>
            EitherT(RawRorConfig.fromString(configString).map(_.left.map(error => Failure(error.show))))
          case None =>
            liftFailure("Malformed settings payload - settings key value is not string")
        }
      case _ =>
        liftFailure("Malformed settings payload - only one settings value allowed")
    }
  }

  private def forceReloadAndSaveNewConfig(config: RawRorConfig) = {
    EitherT(rorInstance.forceReloadAndSave(config))
      .leftMap {
        case IndexConfigSavingError(error) =>
          Failure(s"Cannot save new settings: ${error.show}")
        case ReloadError(RawConfigReloadError.ConfigUpToDate) =>
          Failure(s"Current settings are already loaded")
        case ReloadError(RawConfigReloadError.RorInstanceStopped) =>
          Failure(s"ROR instance is being stopped")
        case ReloadError(RawConfigReloadError.ReloadingFailed(failure)) =>
          Failure(s"Cannot reload new settings: ${failure.message}")
      }
  }

  private def forceReloadTestConfig(config: RawRorConfig,
                                    ttl: Option[FiniteDuration]) = {
    EitherT(rorInstance.forceReloadImpersonatorsEngine(config, ttl))
      .leftMap {
        case RawConfigReloadError.ReloadingFailed(failure) =>
          Failure(s"Cannot reload new settings: ${failure.message}")
        case RawConfigReloadError.ConfigUpToDate =>
          Failure(s"Current settings are already loaded")
        case RawConfigReloadError.RorInstanceStopped =>
          Failure(s"ROR instance is being stopped")
      }
  }

  private def testConfigTtlFrom(headers: Map[String, NonEmptyList[String]]) = {
    headers
      .find { case (name, _) => name.toLowerCase == Constants.HEADER_TEST_CONFIG_TTL}
      .map { case (_, values) => values.head }
      .flatMap { value =>
        Try(Duration(value)).toOption match {
          case Some(v: FiniteDuration) if v.toMillis > 0 => Some(v)
          case Some(_) | None =>
            logger.warn(s"Cannot parse '$value' of ${Constants.HEADER_TEST_CONFIG_TTL} header.")
            None
        }
      }
  }
}

object AdminRestApi {

  final case class AdminRequest(aType: AdminRequest.Type,
                                method: String,
                                uri: String,
                                headers: Map[String, NonEmptyList[String]],
                                body: String)
  object AdminRequest {
    sealed trait Type
    object Type {
      case object ForceReload extends Type
      case object ProvideIndexConfig extends Type
      case object ProvideFileConfig extends Type
      case object UpdateIndexConfig extends Type
      case object UpdateTestConfig extends Type
      case object InvalidateTestConfig extends Type
      case object CurrentUserMetadata extends Type
    }
  }
  final case class AdminResponse(result: ApiCallResult)
  object AdminResponse {
    def notAvailable: AdminResponse = AdminResponse(Failure("Service not available"))

    def internalError: AdminResponse = AdminResponse(Failure("Internal error"))
  }

  sealed trait ApiCallResult {
    def message: String
  }
  final case class Success(message: String) extends ApiCallResult
  final case class ConfigNotFound(message: String) extends ApiCallResult
  final case class Failure(message: String) extends ApiCallResult
}
