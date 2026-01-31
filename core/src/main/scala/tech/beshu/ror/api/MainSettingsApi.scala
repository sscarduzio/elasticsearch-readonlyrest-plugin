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
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Json}
import io.netty.handler.codec.http.HttpResponseStatus
import monix.eval.Task
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.DefaultRorSchemaAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RequestId}
import tech.beshu.ror.api.MainSettingsApi.*
import tech.beshu.ror.api.MainSettingsApi.MainSettingsRequest.Type
import tech.beshu.ror.api.MainSettingsApi.MainSettingsResponse.*
import tech.beshu.ror.api.MainSettingsApi.MainSettingsResponse.ProvideAuditSettings.AuditOutput
import tech.beshu.ror.api.MainSettingsApi.MainSettingsResponse.ProvideAuditSettings.AuditOutput.{LocalAuditIndex, OtherAuditOutput}
import tech.beshu.ror.boot.RorInstance.IndexSettingsReloadWithUpdateError.{IndexSettingsSavingError, ReloadError}
import tech.beshu.ror.boot.RorInstance.{IndexSettingsReloadError, RawSettingsReloadError}
import tech.beshu.ror.boot.{RorInstance, RorSchedulers}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.ror.source.IndexSettingsSource.LoadingError.IndexNotFound
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.SettingsLoadingError.SourceSpecificError
import tech.beshu.ror.settings.ror.source.{FileSettingsSource, IndexSettingsSource}
import tech.beshu.ror.settings.ror.{MainRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.utils.CirceOps.{toCirceErrorOps, toJava}
import tech.beshu.ror.utils.RequestIdAwareLogging

class MainSettingsApi(rorInstance: RorInstance,
                      settingsYamlParser: RawRorSettingsYamlParser,
                      mainSettingsIndexSource: IndexSettingsSource[MainRorSettings],
                      mainSettingsFileSource: FileSettingsSource[MainRorSettings])
  extends RequestIdAwareLogging {

  import MainSettingsApi.Utils.*
  import MainSettingsApi.Utils.decoders.*

  def call(request: MainSettingsRequest)
          (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    val settingsResponse = request.aType match {
      case Type.ForceReload => forceReloadRor()
      case Type.ProvideAuditSettings => provideAuditIndexSettings()
      case Type.ProvideIndexSettings => provideRorIndexSettings()
      case Type.ProvideFileSettings => provideRorFileSettings()
      case Type.UpdateIndexSettings => updateRorIndexSettings(request.body)
    }
    settingsResponse
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def provideAuditIndexSettings(): Task[ProvideAuditSettings] = Task.delay {
    (for {
      engines <- rorInstance.engines.toRight("Not ready")
      auditingSettings <- engines.mainEngine.core.auditingSettings.toRight("Audit not configured")
      sinks = auditingSettings.auditSinks
      auditOutputs = sinks.toList.flatMap {
        case AuditSink.Enabled(config) => config match {
          case Config.EsIndexBasedSink(logSerializer, rorAuditIndexTemplate, AuditCluster.LocalAuditCluster) =>
            logSerializer match {
              case serializer: EcsV1AuditLogSerializer =>
                Some(LocalAuditIndex(rorAuditIndexTemplate.rawKibanaIndexPattern, "ecsV1"))
              case withDefaultSchema: DefaultRorSchemaAuditLogSerializer =>
                Some(LocalAuditIndex(rorAuditIndexTemplate.rawKibanaIndexPattern, "rorDefault"))
              case other =>
                Some(LocalAuditIndex(rorAuditIndexTemplate.rawKibanaIndexPattern, "custom"))
            }
          case Config.EsIndexBasedSink(_, _, _) =>
            Some(OtherAuditOutput("Remote audit cluster"))
          case Config.EsDataStreamBasedSink(_, ds, _) =>
            Some(OtherAuditOutput(s"Data stream with name [${ds.dataStream.value.value}]"))
          case Config.LogBasedSink(_, loggerName) =>
            Some(OtherAuditOutput(s"Logger with name [${loggerName.value.value}]"))
        }
        case AuditSink.Disabled => None
      }
    } yield auditOutputs) match {
      case Left(error) => ProvideAuditSettings.Failure(error)
      case Right(auditOutputs) => ProvideAuditSettings.AuditSettings(auditOutputs)
    }
  }

  private def forceReloadRor()
                            (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    rorInstance
      .forceReloadFromIndex()
      .map {
        case Right(()) =>
          ForceReloadMainSettings.Success("ReadonlyREST settings were reloaded with success!")
        case Left(IndexSettingsReloadError.IndexLoadingSettingsError(error)) =>
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

  private def provideRorFileSettings()
                                    (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    mainSettingsFileSource
      .load()
      .map {
        case Right(settings) => ProvideFileMainSettings.MainSettings(settings.rawSettings.rawYaml)
        case Left(error) => ProvideFileMainSettings.Failure(error.show)
      }
  }

  private def provideRorIndexSettings()
                                     (implicit requestId: RequestId): Task[MainSettingsResponse] = {
    mainSettingsIndexSource
      .load()
      .map {
        case Right(settings) =>
          ProvideIndexMainSettings.MainSettings(settings.rawSettings.rawYaml)
        case Left(SourceSpecificError(error@IndexNotFound)) =>
          ProvideIndexMainSettings.MainSettingsNotFound(Show[IndexSettingsSource.LoadingError].show(error))
        case Left(error) =>
          ProvideIndexMainSettings.Failure(error.show)
      }
  }

  private def decodeUpdateRequest(payload: String): Either[MainSettingsResponse.Failure, UpdateSettingsRequest] = {
    io.circe.parser.decode[UpdateSettingsRequest](payload)
      .left.map(error => MainSettingsResponse.Failure.BadRequest(s"JSON body malformed: [${error.getPrettyMessage.show}]"))
  }

  private def rorMainSettingsFrom(settingsString: String): EitherT[Task, MainSettingsResponse, RawRorSettings] = {
    settingsYamlParser
      .fromString(settingsString)
      .left.map(error => UpdateIndexMainSettings.Failure(error.show): MainSettingsResponse)
      .toEitherT[Task]
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

object MainSettingsApi {

  final class Creator(settingsYamlParser: RawRorSettingsYamlParser,
                      mainSettingsIndexSource: IndexSettingsSource[MainRorSettings],
                      mainSettingsFileSource: FileSettingsSource[MainRorSettings]) {

    def create(rorInstance: RorInstance): MainSettingsApi = {
      new MainSettingsApi(rorInstance, settingsYamlParser, mainSettingsIndexSource, mainSettingsFileSource)
    }
  }

  final case class MainSettingsRequest(aType: MainSettingsRequest.Type,
                                       body: String)
  object MainSettingsRequest {
    sealed trait Type
    object Type {
      case object ForceReload extends Type
      case object ProvideIndexSettings extends Type
      case object ProvideFileSettings extends Type
      case object UpdateIndexSettings extends Type
      case object ProvideAuditSettings extends Type
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

    sealed trait ProvideAuditSettings extends MainSettingsResponse

    object ProvideAuditSettings {
      final case class AuditSettings(auditOutputs: List[AuditOutput]) extends ProvideAuditSettings
      sealed trait AuditOutput
      object AuditOutput {
        final case class LocalAuditIndex(indexPattern: String, schema: String) extends AuditOutput
        final case class OtherAuditOutput(description: String) extends AuditOutput
      }
      final case class Failure(message: String) extends ProvideAuditSettings
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
      case _: ProvideAuditSettings.AuditSettings => "ok"
      case _: ProvideAuditSettings.Failure => "ko"
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

  def buildResponse(builder: EsJsonFromMapBuilder, response: MainSettingsApi.MainSettingsResponse): Unit = {
    response match {
      case forceReloadSettings: MainSettingsResponse.ForceReloadMainSettings => forceReloadSettings match {
        case ForceReloadMainSettings.Success(message) => addResponseJson(builder, response.status, message)
        case ForceReloadMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideIndexSettings: MainSettingsResponse.ProvideIndexMainSettings => provideIndexSettings match {
        case ProvideIndexMainSettings.MainSettings(rawSettings) => addResponseJson(builder, response.status, rawSettings)
        case ProvideIndexMainSettings.MainSettingsNotFound(message) => addResponseJson(builder, response.status, message)
        case ProvideIndexMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideFileSettings: MainSettingsResponse.ProvideFileMainSettings => provideFileSettings match {
        case ProvideFileMainSettings.MainSettings(rawSettings) => addResponseJson(builder, response.status, rawSettings)
        case ProvideFileMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case provideAuditSettings: MainSettingsResponse.ProvideAuditSettings => provideAuditSettings match {
        case ProvideAuditSettings.AuditSettings(auditOutputs) => addResponseJson(builder, response.status, auditOutputs)
        case ProvideAuditSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case updateIndexSettings: MainSettingsResponse.UpdateIndexMainSettings => updateIndexSettings match {
        case UpdateIndexMainSettings.Success(message) => addResponseJson(builder, response.status, message)
        case UpdateIndexMainSettings.Failure(message) => addResponseJson(builder, response.status, message)
      }
      case failure: MainSettingsResponse.Failure => failure match {
        case Failure.BadRequest(message) => addResponseJson(builder, response.status, message)
      }
    }
  }

  def httpStatus(response: MainSettingsApi.MainSettingsResponse): HttpResponseStatus = {
    response match {
      case _: ForceReloadMainSettings => HttpResponseStatus.OK
      case _: ProvideIndexMainSettings => HttpResponseStatus.OK
      case _: ProvideFileMainSettings => HttpResponseStatus.OK
      case _: ProvideAuditSettings => HttpResponseStatus.OK
      case _: UpdateIndexMainSettings => HttpResponseStatus.OK
      case failure: Failure => failure match {
        case Failure.BadRequest(_) => HttpResponseStatus.BAD_REQUEST
      }
    }
  }

  private def addResponseJson(builder: EsJsonFromMapBuilder, status: String, message: String): Unit = {
    builder.build(
      Json.obj(
        "status" -> status.asJson,
        "message" -> message.asJson,
      ).toJava.asInstanceOf[java.util.Map[String, Any]]
    )
  }

  private def addResponseJson(builder: EsJsonFromMapBuilder, status: String, auditOutputs: List[AuditOutput]): Unit = {
    val localAuditIndexes = auditOutputs.collect { case index: AuditOutput.LocalAuditIndex => index }
    val otherAuditOutputs = auditOutputs.collect { case output: AuditOutput.OtherAuditOutput => output }
    builder.build(
      Json.obj(
        "status" -> status.asJson,
        "localAuditIndexes" -> localAuditIndexes.map{ index =>
          Json.obj(
            "indexPattern" -> index.indexPattern.asJson,
            "schema" -> index.schema.asJson,
          )
        }.asJson,
        "otherAuditOutputs" -> otherAuditOutputs.map{ output =>
          Json.obj(
            "description" -> output.description.asJson,
          )
        }.asJson,
      ).toJava.asInstanceOf[java.util.Map[String, Any]]
    )
  }
}
