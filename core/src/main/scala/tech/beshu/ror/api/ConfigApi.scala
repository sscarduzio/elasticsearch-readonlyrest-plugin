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

import cats.Show
import cats.data.EitherT
import cats.implicits.*
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.accesscontrol.domain.{RequestId, RorConfigurationIndex}
import tech.beshu.ror.api.ConfigApi.*
import tech.beshu.ror.api.ConfigApi.ConfigRequest.Type
import tech.beshu.ror.api.ConfigApi.ConfigResponse.*
import tech.beshu.ror.boot.RorInstance.IndexConfigReloadWithUpdateError.{IndexConfigSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexConfigReloadError, RawConfigReloadError}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig}
import tech.beshu.ror.configuration.index.IndexConfigError.IndexConfigNotExist
import tech.beshu.ror.configuration.index.{IndexConfigError, IndexConfigManager}
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.SpecializedError
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.utils.CirceOps.toCirceErrorOps

class ConfigApi(rorInstance: RorInstance,
                indexConfigManager: IndexConfigManager,
                fileConfigLoader: FileConfigLoader,
                rorConfigurationIndex: RorConfigurationIndex)
               (implicit val EnvironmentConfig: EnvironmentConfig)
  extends RequestIdAwareLogging {

  import ConfigApi.Utils.*
  import ConfigApi.Utils.decoders.*

  def call(request: ConfigRequest)
          (implicit requestId: RequestId): Task[ConfigResponse] = {
    val configResponse = request.aType match {
      case Type.ForceReload => forceReloadRor()
      case Type.ProvideIndexConfig => provideRorIndexConfig()
      case Type.ProvideFileConfig => provideRorFileConfig()
      case Type.UpdateIndexConfig => updateIndexConfiguration(request.body)
    }
    configResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def forceReloadRor()
                            (implicit requestId: RequestId): Task[ConfigResponse] = {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(_) =>
          ForceReloadConfig.Success("ReadonlyREST settings were reloaded with success!")
        case Left(IndexConfigReloadError.LoadingConfigError(error)) =>
          ForceReloadConfig.Failure(error.show)
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate(_))) =>
          ForceReloadConfig.Failure("Current settings are already loaded")
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)) =>
          ForceReloadConfig.Failure("ROR is stopped")
        case Left(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(failure))) =>
          ForceReloadConfig.Failure(s"Cannot reload new settings: ${failure.message.show}")
      }
  }

  private def updateIndexConfiguration(body: String)
                                      (implicit requestId: RequestId): Task[ConfigResponse] = {
    val result = for {
      updateRequest <- EitherT.fromEither[Task](decodeUpdateRequest(body))
      config <- rorConfigFrom(updateRequest.configString)
      _ <- forceReloadAndSaveNewConfig(config)
    } yield UpdateIndexConfig.Success("updated settings")

    result.value.map(_.merge)
  }

  private def provideRorFileConfig(): Task[ConfigResponse] = {
    fileConfigLoader
      .load()
      .map {
        case Right(config) => ProvideFileConfig.Config(config.raw)
        case Left(error) => ProvideFileConfig.Failure(error.show)
      }
  }

  private def provideRorIndexConfig(): Task[ConfigResponse] = {
    indexConfigManager
      .load(rorConfigurationIndex)
      .map {
        case Right(config) =>
          ProvideIndexConfig.Config(config.raw)
        case Left(SpecializedError(error: IndexConfigNotExist.type)) =>
          ProvideIndexConfig.ConfigNotFound(Show[IndexConfigError].show(error))
        case Left(error) =>
          ProvideIndexConfig.Failure(error.show)
      }
  }

  private def decodeUpdateRequest(payload: String): Either[ConfigResponse.Failure, UpdateConfigRequest] = {
    io.circe.parser.decode[UpdateConfigRequest](payload)
      .left.map(error => ConfigResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage.show}]"))
  }

  private def rorConfigFrom(configString: String): EitherT[Task, ConfigResponse, RawRorConfig] = EitherT {
    RawRorConfig
      .fromString(configString)
      .map(_.left.map(error => UpdateIndexConfig.Failure(error.show)))
  }

  private def forceReloadAndSaveNewConfig(config: RawRorConfig)
                                         (implicit requestId: RequestId): EitherT[Task, ConfigResponse, Unit] = {
    EitherT(rorInstance.forceReloadAndSave(config))
      .leftMap {
        case IndexConfigSavingError(error) =>
          UpdateIndexConfig.Failure(s"Cannot save new settings: ${error.show}")
        case ReloadError(RawConfigReloadError.ConfigUpToDate(_)) =>
          UpdateIndexConfig.Failure(s"Current settings are already loaded")
        case ReloadError(RawConfigReloadError.RorInstanceStopped) =>
          UpdateIndexConfig.Failure(s"ROR instance is being stopped")
        case ReloadError(RawConfigReloadError.ReloadingFailed(failure)) =>
          UpdateIndexConfig.Failure(s"Cannot reload new settings: ${failure.message.show}")
      }
  }
}

object ConfigApi {

  final case class ConfigRequest(aType: ConfigRequest.Type,
                                 body: String)
  object ConfigRequest {
    sealed trait Type
    object Type {
      case object ForceReload extends Type
      case object ProvideIndexConfig extends Type
      case object ProvideFileConfig extends Type
      case object UpdateIndexConfig extends Type
    }
  }

  sealed trait ConfigResponse
  object ConfigResponse {
    sealed trait ForceReloadConfig extends ConfigResponse
    object ForceReloadConfig {
      final case class Success(message: String) extends ForceReloadConfig
      final case class Failure(message: String) extends ForceReloadConfig
    }

    sealed trait ProvideIndexConfig extends ConfigResponse
    object ProvideIndexConfig {
      final case class Config(rawConfig: String) extends ProvideIndexConfig
      final case class ConfigNotFound(message: String) extends ProvideIndexConfig
      final case class Failure(message: String) extends ProvideIndexConfig
    }

    sealed trait ProvideFileConfig extends ConfigResponse
    object ProvideFileConfig {
      final case class Config(rawConfig: String) extends ProvideFileConfig
      final case class Failure(message: String) extends ProvideFileConfig
    }

    sealed trait UpdateIndexConfig extends ConfigResponse
    object UpdateIndexConfig {
      final case class Success(message: String) extends UpdateIndexConfig
      final case class Failure(message: String) extends UpdateIndexConfig
    }

    sealed trait Failure extends ConfigResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  implicit class StatusFromConfigResponse(val response: ConfigResponse) extends AnyVal {
    def status: String = response match {
      case _: ForceReloadConfig.Success => "ok"
      case _: ForceReloadConfig.Failure => "ko"
      case _: ProvideIndexConfig.Config => "ok"
      case _: ProvideIndexConfig.ConfigNotFound => "empty"
      case _: ProvideIndexConfig.Failure => "ko"
      case _: ProvideFileConfig.Config => "ok"
      case _: ProvideFileConfig.Failure => "ko"
      case _: UpdateIndexConfig.Success => "ok"
      case _: UpdateIndexConfig.Failure => "ko"
      case failure: ConfigResponse.Failure => failure match {
        case Failure.BadRequest(_) => "ko"
      }
    }
  }

  private object Utils {
    final case class UpdateConfigRequest(configString: String)

    object decoders {
      implicit val updateConfigRequestDecoder: Decoder[UpdateConfigRequest] =
        Decoder.forProduct1("settings")(UpdateConfigRequest.apply)
    }
  }
}
