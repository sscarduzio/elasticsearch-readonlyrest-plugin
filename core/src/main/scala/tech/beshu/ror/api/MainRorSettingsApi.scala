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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.api.MainRorSettingsApi.*
import tech.beshu.ror.api.MainRorSettingsApi.MainSettingsRequest.Type
import tech.beshu.ror.api.MainRorSettingsApi.MainSettingsResponse.*
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexSettingsReloadError, RawSettingsReloadError}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.manager.RorMainSettingsManager
import tech.beshu.ror.configuration.manager.SettingsManager.LoadingFromIndexError
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.CirceOps.toCirceErrorOps

class MainRorSettingsApi(esConfigBasedRorSettings: EsConfigBasedRorSettings,
                         rorInstance: RorInstance,
                         settingsYamlParser: RawRorSettingsYamlParser,
                         settingsManager: RorMainSettingsManager)
  extends Logging {

  import MainRorSettingsApi.Utils.*
  import MainRorSettingsApi.Utils.decoders.*

  def call(request: MainSettingsRequest)
          (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    val settingsResponse = request.aType match {
      case Type.ForceReload => forceReloadRor()
      case Type.ProvideIndexSettings => provideRorIndexSettings()
      case Type.ProvideFileSettings => provideRorFileSettings()
      case Type.UpdateIndexSettings => updateRorIndexSettings(request.body)
    }
    settingsResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def forceReloadRor()
                            (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(_) =>
          ForceReloadMainSettings.Success("ReadonlyREST settings were reloaded with success!")
        case Left(IndexSettingsReloadError.LoadingSettingsError(error)) =>
          ForceReloadMainSettings.Failure(error.show)
        case Left(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.SettingsUpToDate(_))) =>
          ForceReloadMainSettings.Failure("Current settings are already loaded")
        case Left(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.RorInstanceStopped)) =>
          ForceReloadMainSettings.Failure("ROR is stopped")
        case Left(IndexSettingsReloadError.ReloadError(RawSettingsReloadError.ReloadingFailed(failure))) =>
          ForceReloadMainSettings.Failure(s"Cannot reload new settings: ${failure.message.show}")
      }
  }

  private def updateRorIndexSettings(body: String)
                                    (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    val result = for {
      updateRequest <- EitherT.fromEither[Task](decodeUpdateRequest(body))
      newRorSettings <- rorMainSettingsFrom(updateRequest.settingsString)
      _ <- forceReloadAndSaveNewSettings(newRorSettings)
    } yield UpdateIndexMainSettings.Success("updated settings")

    result.value.map(_.merge)
  }

  private def provideRorFileSettings(): Task[MainSettingsResponse] = {
    settingsManager
      .loadFromFile {
        esConfigBasedRorSettings.loadingRorCoreStrategy match {
          case LoadingRorCoreStrategy.ForceLoadingFromFile(parameters) => parameters
          case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(_, fallbackParameters) => fallbackParameters
        }
      }
      .map {
        case Right(settings) => ProvideFileMainSettings.MainSettings(settings.raw)
        case Left(error) => ProvideFileMainSettings.Failure(error.show)
      }
  }

  private def provideRorIndexSettings(): Task[MainSettingsResponse] = {
    settingsManager
      .loadFromIndex()
      .map {
        case Right(settings) =>
          ProvideIndexMainSettings.MainSettings(settings.raw)
        case Left(error@LoadingFromIndexError.IndexNotExist) =>
          ProvideIndexMainSettings.MainSettingsNotFound(Show[LoadingFromIndexError].show(error))
        case Left(error) => ProvideIndexMainSettings.Failure(error.show)
      }
  }

  private def decodeUpdateRequest(payload: String): Either[MainSettingsResponse.Failure, UpdateSettingsRequest] = {
    io.circe.parser.decode[UpdateSettingsRequest](payload)
      .left.map(error => MainSettingsResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage.show}]"))
  }

  private def rorMainSettingsFrom(settingsString: String): EitherT[Task, MainSettingsResponse, RawRorSettings] = EitherT {
    settingsYamlParser
      .fromString(settingsString)
      .map(_.left.map(error => UpdateIndexMainSettings.Failure(error.show)))
  }

  private def forceReloadAndSaveNewSettings(settings: RawRorSettings)
                                           (implicit requestId: RequestId): EitherT[Task, MainSettingsResponse, Unit] = {
    EitherT(rorInstance.forceReloadAndSave(settings))
      .leftMap {
        case IndexSettingsSavingError(error) =>
          UpdateIndexMainSettings.Failure(s"Cannot save new settings: ${error.show}")
        case ReloadError(RawSettingsReloadError.SettingsUpToDate(_)) =>
          UpdateIndexMainSettings.Failure(s"Current settings are already loaded")
        case ReloadError(RawSettingsReloadError.RorInstanceStopped) =>
          UpdateIndexMainSettings.Failure(s"ROR instance is being stopped")
        case ReloadError(RawSettingsReloadError.ReloadingFailed(failure)) =>
          UpdateIndexMainSettings.Failure(s"Cannot reload new settings: ${failure.message.show}")
      }
  }
}

object MainRorSettingsApi {

  final case class MainSettingsRequest(aType: MainSettingsRequest.Type,
                                       body: String)
  object MainSettingsRequest {
    sealed trait Type
    object Type {
      case object ForceReload extends Type
      case object ProvideIndexSettings extends Type
      case object ProvideFileSettings extends Type
      case object UpdateIndexSettings extends Type
    }
  }

  sealed trait MainSettingsResponse
  object MainSettingsResponse {
    sealed trait ForceReloadMainSettings extends MainSettingsResponse
    object ForceReloadMainSettings {
      final case class Success(message: String) extends ForceReloadMainSettings
      final case class Failure(message: String) extends ForceReloadMainSettings
    }

    sealed trait ProvideIndexMainSettings extends MainSettingsResponse
    object ProvideIndexMainSettings {
      final case class MainSettings(rawSettings: String) extends ProvideIndexMainSettings
      final case class MainSettingsNotFound(message: String) extends ProvideIndexMainSettings
      final case class Failure(message: String) extends ProvideIndexMainSettings
    }

    sealed trait ProvideFileMainSettings extends MainSettingsResponse
    object ProvideFileMainSettings {
      final case class MainSettings(rawSettings: String) extends ProvideFileMainSettings
      final case class Failure(message: String) extends ProvideFileMainSettings
    }

    sealed trait UpdateIndexMainSettings extends MainSettingsResponse
    object UpdateIndexMainSettings {
      final case class Success(message: String) extends UpdateIndexMainSettings
      final case class Failure(message: String) extends UpdateIndexMainSettings
    }

    sealed trait Failure extends MainSettingsResponse
    object Failure {
      final case class BadRequest(message: String) extends Failure
    }
  }

  implicit class StatusFromSettingsResponse(val response: MainSettingsResponse) extends AnyVal {
    def status: String = response match {
      case _: ForceReloadMainSettings.Success => "ok"
      case _: ForceReloadMainSettings.Failure => "ko"
      case _: ProvideIndexMainSettings.MainSettings => "ok"
      case _: ProvideIndexMainSettings.MainSettingsNotFound => "empty"
      case _: ProvideIndexMainSettings.Failure => "ko"
      case _: ProvideFileMainSettings.MainSettings => "ok"
      case _: ProvideFileMainSettings.Failure => "ko"
      case _: UpdateIndexMainSettings.Success => "ok"
      case _: UpdateIndexMainSettings.Failure => "ko"
      case failure: MainSettingsResponse.Failure => failure match {
        case Failure.BadRequest(_) => "ko"
      }
    }
  }

  private object Utils {
    final case class UpdateSettingsRequest(settingsString: String)

    object decoders {
      implicit val updateSettingsRequestDecoder: Decoder[UpdateSettingsRequest] =
        Decoder.forProduct1("settings")(UpdateSettingsRequest.apply)
    }
  }
}
